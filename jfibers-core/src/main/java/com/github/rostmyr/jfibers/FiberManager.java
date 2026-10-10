package com.github.rostmyr.jfibers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** A cooperative scheduler. Only its owner thread executes fiber code. */
public class FiberManager implements AutoCloseable {
    private static final long POLL_INTERVAL_NANOS = 1_000_000L;
    private volatile boolean interrupted;
    private volatile boolean closing;
    private volatile boolean cancelAllRequested;
    private volatile Thread runner;
    private volatile FiberProfiler profiler;
    private Fiber<?> current;
    private Fiber<?> last;
    private Fiber<?> active;
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicBoolean wakeupArmed = new AtomicBoolean();
    private final ConcurrentLinkedQueue<FiberWait> signals = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Fiber<?>> cancellations = new ConcurrentLinkedQueue<>();
    private final Set<FiberWait> polled = new HashSet<>();
    private final PriorityQueue<Fiber<?>> deadlines = new PriorityQueue<>(
        (a, b) -> Long.compare(a.deadlineNanos - b.deadlineNanos, 0));
    private volatile long nextPoll;

    /** Schedule before run(), or from the thread currently executing run(). */
    public void schedule(Fiber<?> fiber) {
        schedule(fiber, false);
    }

    void scheduleChild(Fiber<?> parent, Fiber<?> child) {
        schedule(child, true);
        child.parent = parent;
        if (parent.children == null) {
            parent.children = new ArrayList<>();
        }
        parent.children.add(child);
    }

    private void schedule(Fiber<?> fiber, boolean child) {
        Objects.requireNonNull(fiber, "fiber");
        checkOwner();
        if ((!child && closing) || (interrupted && !closing)) {
            throw new IllegalStateException("Fiber manager is stopped or shutting down");
        }
        synchronized (fiber) {
            if (fiber.scheduler != null || fiber.isReady()) {
                throw new IllegalStateException("Fiber is already scheduled or completed");
            }
            fiber.scheduler = this;
            if (fiber.timeoutNanos() >= 0) {
                fiber.deadlineNanos = System.nanoTime() + fiber.timeoutNanos();
                fiber.deadlineQueued = true;
                deadlines.add(fiber);
            }
        }
        fiber.activeNext = active;
        if (active != null) {
            active.activePrevious = fiber;
        }
        active = fiber;
        size.incrementAndGet();
        enqueue(fiber);
    }

    private void checkOwner() {
        Thread owner = runner;
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException("Schedule fibers on the scheduler thread");
        }
    }

    private void enqueue(Fiber<?> fiber) {
        if (fiber.queued) {
            return;
        }
        fiber.queued = true;
        fiber.status(FiberStatus.RUNNABLE);
        fiber.next = null;
        if (last == null) {
            current = fiber;
        } else {
            last.next = fiber;
        }
        last = fiber;
        if (FiberProfiler.ENABLED && profiler != null) {
            profiler.enqueue();
        }
    }

    /** Includes ready and waiting fibers; safe to read from another thread. */
    public int getSize() {
        return size.get();
    }

    /** Runs until all fibers complete or stop() is requested. Parks when only waits remain. */
    public void run() {
        synchronized (this) {
            if (runner != null) {
                throw new IllegalStateException("Fiber manager is already running");
            }
            runner = Thread.currentThread();
        }
        boolean restoreInterrupt = false;
        try {
            if (FiberProfiler.ENABLED && size.get() != 0) {
                profiler = new FiberProfiler(current);
            }
            while ((!interrupted || closing) && size.get() != 0) {
                if (Thread.currentThread().isInterrupted()) {
                    if (closing) {
                        restoreInterrupt |= Thread.interrupted();
                    } else {
                        stop();
                        break;
                    }
                }
                expireDeadlines();
                drainCancellations();
                drainSignals();
                pollFutures();
                Fiber<?> fiber = dequeue();
                if (fiber == null) {
                    park();
                } else {
                    execute(fiber);
                }
            }
        } finally {
            try {
                if (FiberProfiler.ENABLED && profiler != null) {
                    FiberProfiler completedProfile = profiler;
                    profiler = null;
                    completedProfile.finish(size.get());
                }
            } finally {
                if (size.get() == 0) {
                    cancellations.clear();
                    signals.clear();
                }
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
                synchronized (this) {
                    runner = null;
                    notifyAll();
                }
            }
        }
    }

    private Fiber<?> dequeue() {
        Fiber<?> fiber = current;
        if (fiber != null) {
            current = fiber.next;
            if (current == null) {
                last = null;
            }
            fiber.next = null;
            fiber.queued = false;
            if (FiberProfiler.ENABLED && profiler != null) {
                profiler.dequeue();
            }
        }
        return fiber;
    }

    private void execute(Fiber<?> fiber) {
        FiberProfiler trace = FiberProfiler.ENABLED ? profiler : null;
        long started = trace == null ? 0 : trace.beforeExecute(fiber);
        try {
            executeFiber(fiber);
        } finally {
            if (trace != null) {
                trace.afterExecute(started, size.get());
            }
        }
    }

    private void executeFiber(Fiber<?> fiber) {
        fiber.status(FiberStatus.RUNNING);
        if (fiber.finishing) {
            finish(fiber);
            return;
        }
        if (fiber.termination() != null && (!fiber.started || !fiber.supportsCancellationUnwind())) {
            fiber.fail(fiber.termination());
            complete(fiber);
            return;
        }
        fiber.started = true;
        try {
            int nextState = fiber.update();
            if (nextState == -1) {
                complete(fiber);
            } else {
                fiber.setState(nextState);
                if (!suspend(fiber)) {
                    enqueue(fiber);
                }
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            fiber.fail(failure);
            complete(fiber);
        }
    }

    private void park() {
        long delay = Long.MAX_VALUE;
        if (!polled.isEmpty() || !deadlines.isEmpty()) {
            long now = System.nanoTime();
            if (!polled.isEmpty()) {
                delay = Math.max(1, nextPoll - now);
            }
            if (!deadlines.isEmpty()) {
                delay = Math.min(delay, Math.max(1, deadlines.peek().deadlineNanos - now));
            }
        }
        // Publish intent before checking mailboxes: a racing producer either appears in
        // this check or supplies an unpark permit for the park below. Only one producer
        // needs to unpark; notifications remain in the queues until the owner drains them.
        wakeupArmed.set(true);
        try {
            if (!signals.isEmpty() || !cancellations.isEmpty() || cancelAllRequested || (interrupted && !closing)) {
                return;
            }
            FiberProfiler trace = FiberProfiler.ENABLED ? profiler : null;
            long started = trace == null ? 0 : trace.beforePark();
            try {
                if (delay == Long.MAX_VALUE) {
                    LockSupport.park(this);
                } else {
                    LockSupport.parkNanos(this, delay);
                }
            } finally {
                if (trace != null) {
                    trace.afterPark(started, size.get());
                }
            }
        } finally {
            wakeupArmed.set(false);
        }
    }

    private boolean suspend(Fiber<?> fiber) {
        if (fiber.current != null && !fiber.current.isReady()) {
            FiberWait wait = new FiberWait(this, fiber);
            wait.child = fiber.current;
            fiber.wait = wait;
            if (fiber.current.dependents == null) {
                fiber.current.dependents = new ArrayList<>();
            }
            fiber.current.dependents.add(wait);
            fiber.status(FiberStatus.WAITING);
            if (FiberProfiler.ENABLED && profiler != null) {
                profiler.waiting(wait, "child");
            }
            return true;
        }
        if (fiber.future != null && !fiber.future.isDone()) {
            FiberWait wait = new FiberWait(this, fiber);
            fiber.wait = wait;
            fiber.status(FiberStatus.WAITING);
            if (fiber.future instanceof CompletionStage<?> stage) {
                if (FiberProfiler.ENABLED && profiler != null) {
                    profiler.waiting(wait, "completionStage");
                }
                stage.whenComplete(wait);
            } else {
                if (FiberProfiler.ENABLED && profiler != null) {
                    profiler.waiting(wait, "poll");
                }
                polled.add(wait);
            }
            return true;
        }
        return false;
    }

    private void drainSignals() {
        FiberWait wait;
        while ((wait = signals.poll()) != null) {
            resume(wait);
        }
    }

    private void resume(FiberWait wait) {
        resume(wait, false);
    }

    private void resume(FiberWait wait, boolean cancelled) {
        Fiber<?> fiber = wait.fiber;
        if (fiber != null && fiber.wait == wait) {
            if (FiberProfiler.ENABLED && profiler != null) {
                profiler.resumed(wait, fiber, cancelled);
            }
            detachWait(fiber);
            enqueue(fiber);
        } else if (FiberProfiler.ENABLED && profiler != null) {
            profiler.staleSignal();
        }
    }

    private void detachWait(Fiber<?> fiber) {
        FiberWait wait = fiber.wait;
        if (wait == null) {
            return;
        }
        if (FiberProfiler.ENABLED && profiler != null) {
            profiler.detached(wait);
        }
        polled.remove(wait);
        if (wait.child != null && wait.child.dependents != null) {
            wait.child.dependents.remove(wait);
        }
        wait.dispose();
        fiber.wait = null;
    }

    private void pollFutures() {
        if (polled.isEmpty() || System.nanoTime() - nextPoll < 0) {
            return;
        }
        nextPoll = System.nanoTime() + POLL_INTERVAL_NANOS;
        var iterator = polled.iterator();
        while (iterator.hasNext()) {
            FiberWait wait = iterator.next();
            boolean done;
            try {
                done = wait.fiber.future.isDone();
            } catch (RuntimeException e) {
                done = true;
            }
            if (done) {
                iterator.remove();
                resume(wait);
            }
        }
    }

    private void expireDeadlines() {
        if (deadlines.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        while (!deadlines.isEmpty() && now - deadlines.peek().deadlineNanos >= 0) {
            Fiber<?> fiber = deadlines.remove();
            fiber.deadlineQueued = false;
            fiber.requestTermination(new TimeoutException("Fiber deadline exceeded"));
        }
    }

    void cancelRequested(Fiber<?> fiber) {
        cancellations.add(fiber);
        wakeup();
    }

    private void drainCancellations() {
        if (cancelAllRequested) {
            cancelAllRequested = false;
            for (Fiber<?> fiber = active; fiber != null; fiber = fiber.activeNext) {
                fiber.cancel();
            }
        }
        Fiber<?> fiber;
        while ((fiber = cancellations.poll()) != null) {
            if (fiber.scheduler != this) {
                continue;
            }
            cancelChildren(fiber);
            if (fiber.wait != null) {
                resume(fiber.wait, true);
            }
        }
    }

    private void cancelChildren(Fiber<?> fiber) {
        if (fiber.children != null) {
            for (Fiber<?> child : fiber.children) {
                child.cancel();
            }
        }
    }

    private void complete(Fiber<?> fiber) {
        detachWait(fiber);
        if (fiber.children != null && !fiber.children.isEmpty()) {
            fiber.finishing = true;
            fiber.status(FiberStatus.WAITING);
            cancelChildren(fiber);
        } else {
            finish(fiber);
        }
    }

    private void finish(Fiber<?> fiber) {
        detachWait(fiber);
        if (fiber.deadlineQueued) {
            deadlines.remove(fiber);
            fiber.deadlineQueued = false;
        }
        synchronized (fiber) {
            if (fiber.termination() != null) {
                // Cancellation is sticky even if user code catches it to perform cleanup.
                Throwable cleanupFailure = fiber.getFailure();
                Exception reason = fiber.termination();
                if (cleanupFailure != null && cleanupFailure != reason) {
                    reason.addSuppressed(cleanupFailure);
                }
                fiber.fail(reason);
            }
            fiber.current = null;
            fiber.future = null;
            fiber.clearFrame();
            fiber.setState(-1);
            fiber.status(fiber.terminalStatus());
            fiber.scheduler = null;
        }
        if (fiber.activePrevious == null) {
            active = fiber.activeNext;
        } else {
            fiber.activePrevious.activeNext = fiber.activeNext;
        }
        if (fiber.activeNext != null) {
            fiber.activeNext.activePrevious = fiber.activePrevious;
        }
        fiber.activeNext = null;
        fiber.activePrevious = null;
        size.decrementAndGet();
        if (fiber.parent != null) {
            Fiber<?> parent = fiber.parent;
            if (parent.finishing && fiber.getFailure() != null
                && (!(fiber.getFailure() instanceof java.util.concurrent.CancellationException)
                    || fiber.getFailure().getSuppressed().length != 0)) {
                Throwable parentFailure = parent.getFailure() != null ? parent.getFailure() : parent.termination();
                if (parentFailure == null) {
                    parent.fail(fiber.getFailure());
                } else if (parentFailure != fiber.getFailure()) {
                    parentFailure.addSuppressed(fiber.getFailure());
                }
            }
            parent.children.remove(fiber);
            fiber.parent = null;
            if (parent.finishing && parent.children.isEmpty()) {
                enqueue(parent);
            }
        }
        fiber.children = null;
        if (fiber.dependents != null) {
            var dependents = fiber.dependents;
            fiber.dependents = null;
            for (FiberWait wait : dependents) {
                resume(wait);
            }
        }
    }

    void signal(FiberWait wait) {
        if (FiberProfiler.ENABLED) {
            FiberProfiler trace = profiler;
            if (trace != null) {
                trace.signal(wait);
            }
        }
        signals.add(wait);
        wakeup();
    }

    private void wakeup() {
        if (FiberProfiler.ENABLED) {
            FiberProfiler trace = profiler;
            if (trace != null) {
                trace.wakeRequested();
            }
        }
        if (wakeupArmed.get() && wakeupArmed.compareAndSet(true, false)) {
            if (FiberProfiler.ENABLED) {
                FiberProfiler trace = profiler;
                if (trace != null) {
                    trace.unparked();
                }
            }
            LockSupport.unpark(runner);
        }
    }

    /** Request cancellation of all currently scheduled work at the next scheduler turn. */
    public void cancelAll() {
        cancelAllRequested = true;
        wakeup();
    }

    /** Reject new root tasks and request cancellation; run()/close() drains cooperative cleanup. */
    public void shutdown() {
        closing = true;
        cancelAll();
    }

    /** Drain cancellation and cleanup, including after stop(). Called inside a fiber, requests shutdown only. */
    @Override
    public void close() {
        shutdown();
        synchronized (this) {
            if (runner == Thread.currentThread()) {
                return;
            }
            while (runner != null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for fiber cleanup", e);
                }
            }
            run();
        }
    }

    /** Stop is permanent, preserves unfinished fibers, and wakes an idle run(). close() can still clean them up. */
    public void stop() {
        interrupted = true;
        wakeup();
    }
}
