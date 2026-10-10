package com.github.rostmyr.jfibers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** A cooperative scheduler. Only its owner thread executes fiber code. */
public class FiberManager {
    private static final long POLL_INTERVAL_NANOS = 1_000_000L;

    private volatile boolean interrupted;
    private volatile Thread runner;
    private Fiber<?> current;
    private Fiber<?> last;
    private final AtomicInteger size = new AtomicInteger();
    private final ConcurrentLinkedQueue<FiberWait> signals = new ConcurrentLinkedQueue<>();
    private final Set<FiberWait> polled = new HashSet<>();
    private volatile long nextPoll;

    /** Schedule before run(), or from the thread currently executing run(). */
    public void schedule(Fiber<?> fiber) {
        Objects.requireNonNull(fiber, "fiber");
        checkOwner();
        if (interrupted) {
            throw new IllegalStateException("Fiber manager is stopped");
        }
        if (fiber.scheduler != null) {
            throw new IllegalStateException("Fiber is already scheduled");
        }
        if (fiber.isReady()) {
            throw new IllegalStateException("Fiber has already completed");
        }
        fiber.scheduler = this;
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
        fiber.next = null;
        if (last == null) {
            current = fiber;
        } else {
            last.next = fiber;
        }
        last = fiber;
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
        try {
            while (!interrupted && size.get() != 0) {
                if (Thread.currentThread().isInterrupted()) {
                    stop();
                    break;
                }
                drainSignals();
                pollFutures();
                Fiber<?> fiber = current;
                if (fiber == null) {
                    if (polled.isEmpty()) {
                        LockSupport.park(this);
                    } else {
                        LockSupport.parkNanos(this, Math.max(1, nextPoll - System.nanoTime()));
                    }
                    continue;
                }
                current = fiber.next;
                if (current == null) {
                    last = null;
                }
                fiber.next = null;
                try {
                    fiber.setState(fiber.update());
                    if (fiber.isReady()) {
                        finish(fiber);
                    } else if (!suspend(fiber)) {
                        enqueue(fiber);
                    }
                } catch (Exception e) {
                    fiber.setState(fiber.fail(e));
                    finish(fiber);
                }
            }
        } finally {
            runner = null;
        }
    }

    private boolean suspend(Fiber<?> fiber) {
        if (fiber.current != null && !fiber.current.isReady()) {
            FiberWait wait = new FiberWait(this, fiber);
            fiber.wait = wait;
            if (fiber.current.dependents == null) {
                fiber.current.dependents = new ArrayList<>();
            }
            fiber.current.dependents.add(wait);
            return true;
        }
        if (fiber.future != null && !fiber.future.isDone()) {
            FiberWait wait = new FiberWait(this, fiber);
            fiber.wait = wait;
            if (fiber.future instanceof CompletionStage<?> stage) {
                stage.whenComplete(wait);
            } else {
                // Future has no notification API. Keep this fallback bounded, even when idle.
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
        Fiber<?> fiber = wait.fiber;
        if (fiber != null && fiber.wait == wait) {
            fiber.wait = null;
            wait.dispose();
            enqueue(fiber);
        }
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
                done = true; // The fiber's next update will record the error.
            }
            if (done) {
                iterator.remove();
                resume(wait);
            }
        }
    }

    private void finish(Fiber<?> fiber) {
        if (fiber.wait != null) {
            polled.remove(fiber.wait);
            fiber.wait.dispose();
            fiber.wait = null;
        }
        fiber.scheduler = null;
        size.decrementAndGet();
        if (fiber.dependents != null) {
            for (FiberWait wait : fiber.dependents) {
                resume(wait);
            }
            fiber.dependents = null;
        }
    }

    void signal(FiberWait wait) {
        signals.add(wait);
        LockSupport.unpark(runner);
    }

    /** Stop is permanent, preserves unfinished fibers, and wakes an idle run(). */
    public void stop() {
        interrupted = true;
        LockSupport.unpark(runner);
    }
}
