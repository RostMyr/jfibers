package com.github.rostmyr.jfibers;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounded admission to independent, single-owner schedulers. A submitted root and its owned
 * children stay on one owner; roots are assigned round-robin, without migration or work stealing.
 */
public final class FiberPool implements AutoCloseable {
    private static final AtomicInteger IDS = new AtomicInteger();
    private final Object lock = new Object();
    private final Worker[] workers;
    private final int capacity;
    private final Set<FiberTask<?>> tasks = new HashSet<>();
    private int nextWorker;
    private int liveWorkers;
    private boolean shutdown;

    /**
     * Capacity bounds accepted roots across queued, running, waiting and cancellation-cleanup states.
     * Owned children are included in their root's lifetime, not counted as separate admission slots.
     * Workers are non-daemon platform threads; close the pool or shut it down explicitly.
     */
    public FiberPool(int parallelism, int capacity) {
        if (parallelism < 1 || capacity < 1) {
            throw new IllegalArgumentException("Parallelism and capacity must be positive");
        }
        this.capacity = capacity;
        workers = new Worker[parallelism];
        liveWorkers = parallelism;
        int poolId = IDS.incrementAndGet();
        for (int i = 0; i < parallelism; i++) {
            workers[i] = new Worker("jfibers-pool-" + poolId + "-" + i);
        }
        int started = 0;
        try {
            for (Worker worker : workers) {
                worker.thread.start();
                started++;
            }
        } catch (Throwable failure) {
            synchronized (lock) {
                liveWorkers -= workers.length - started;
            }
            shutdownNow();
            throw failure;
        }
    }

    /**
     * Submit a factory for a fresh, unscheduled fiber. The factory executes on the selected owner;
     * keep it short. Factory failures fail this submission. Admission is nonblocking and rejects
     * overload synchronously; rejected factories never execute. Configure withTimeout inside the
     * factory: its deadline starts when that fiber is scheduled, after admission queueing.
     */
    public <T> FiberTask<T> submit(Supplier<? extends Fiber<T>> factory) {
        Objects.requireNonNull(factory, "factory");
        FiberTask<T> task;
        CompletableFuture<Void> wake;
        synchronized (lock) {
            if (shutdown) {
                throw new RejectedExecutionException("Fiber pool is shut down");
            }
            if (tasks.size() == capacity) {
                throw new RejectedExecutionException("Fiber pool capacity exhausted: " + capacity);
            }
            Worker worker = workers[nextWorker];
            nextWorker = (nextWorker + 1) % workers.length;
            task = new FiberTask<>(this, worker, factory);
            tasks.add(task);
            worker.pending.addLast(task);
            worker.taskCount++;
            wake = worker.wake;
        }
        wake.complete(null);
        return task;
    }

    public int getParallelism() {
        return workers.length;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getTaskCount() {
        synchronized (lock) {
            return tasks.size();
        }
    }

    public boolean isShutdown() {
        synchronized (lock) {
            return shutdown;
        }
    }

    public boolean isTerminated() {
        synchronized (lock) {
            return liveWorkers == 0;
        }
    }

    /** Reject further submissions, finish all accepted work, then stop idle owners. */
    public void shutdown() {
        List<CompletableFuture<Void>> wakes = new ArrayList<>(workers.length);
        synchronized (lock) {
            shutdown = true;
            for (Worker worker : workers) {
                wakes.add(worker.wake);
            }
        }
        wakes.forEach(wake -> wake.complete(null));
    }

    /**
     * Reject further submissions and request cancellation of all accepted work. No owner thread
     * is interrupted. Running factories and fiber code remain cooperative; cleanup may await.
     */
    public void shutdownNow() {
        List<FiberTask<?>> snapshot;
        synchronized (lock) {
            shutdown = true;
            snapshot = new ArrayList<>(tasks);
        }
        for (FiberTask<?> task : snapshot) {
            cancel(task);
        }
        // Also cancel the ingress fiber and any direct manager work scheduled by application code.
        for (Worker worker : workers) {
            worker.manager.shutdown();
        }
    }

    /** Wait for owner termination. Calling this on one of this pool's owners is rejected. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        checkNotOwner();
        long nanos;
        try {
            nanos = Math.min(timeout.toNanos(), Long.MAX_VALUE / 4);
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE / 4;
        }
        long deadline = System.nanoTime() + nanos;
        synchronized (lock) {
            while (liveWorkers != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
        return true;
    }

    /** Cancel and drain. On an owner thread this only requests cancellation, avoiding self-join. */
    @Override
    public void close() {
        shutdownNow();
        if (isOwner()) {
            return;
        }
        synchronized (lock) {
            while (liveWorkers != 0) {
                try {
                    lock.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while draining fiber pool", interrupted);
                }
            }
        }
    }

    private boolean isOwner() {
        Thread current = Thread.currentThread();
        for (Worker worker : workers) {
            if (worker.thread == current) {
                return true;
            }
        }
        return false;
    }

    private void checkNotOwner() {
        if (isOwner()) {
            throw new IllegalStateException("Cannot wait for fiber pool termination on its owner thread");
        }
    }

    boolean isCancellationRequested(FiberTask<?> task) {
        synchronized (lock) {
            return task.cancellationRequested;
        }
    }

    boolean cancel(FiberTask<?> task) {
        boolean queued;
        synchronized (lock) {
            if (task.finished || task.cancellationRequested) {
                return false;
            }
            if (task.fiber != null && !task.fiber.cancel()) {
                return false;
            }
            task.cancellationRequested = true;
            queued = task.queued;
            if (queued) {
                task.worker.pending.remove(task);
                task.queued = false;
            }
        }
        if (queued) {
            finish(task, null, new CancellationException("Fiber pool task cancelled before starting"));
        }
        return true;
    }

    private <T> void finish(FiberTask<T> task, T result, Throwable failure) {
        CompletableFuture<Void> wake;
        synchronized (lock) {
            if (task.finished) {
                return;
            }
            if (task.cancellationRequested && !(failure instanceof CancellationException)) {
                CancellationException cancelled = new CancellationException("Fiber pool task cancelled");
                if (failure != null) {
                    cancelled.addSuppressed(failure);
                }
                failure = cancelled;
            }
            task.finished = true;
            task.factory = null;
            task.fiber = null;
            tasks.remove(task);
            task.worker.taskCount--;
            wake = task.worker.wake;
        }
        wake.complete(null);
        task.publish(result, failure);
    }

    final class Worker implements Runnable {
        final ArrayDeque<FiberTask<?>> pending = new ArrayDeque<>(); // guarded by pool lock
        CompletableFuture<Void> wake = new CompletableFuture<>(); // guarded by pool lock
        int taskCount; // guarded by pool lock
        final Map<Fiber<?>, FiberTask<?>> running = new IdentityHashMap<>(); // owner only
        final FiberManager manager = new FiberManager(this::completed);
        final Thread thread;

        Worker(String name) {
            thread = Thread.ofPlatform().name(name).unstarted(this);
            // Install before starting the thread so immediate graceful shutdown still drains work.
            manager.schedule(new Ingress(this));
        }

        @Override
        public void run() {
            Throwable failure = null;
            FiberManagers.bind(manager);
            try {
                manager.run();
                synchronized (lock) {
                    if (!shutdown || taskCount != 0) {
                        throw new IllegalStateException("Fiber pool owner stopped before draining submissions");
                    }
                }
            } catch (Throwable unexpected) {
                failure = unexpected;
                shutdownNow();
            } finally {
                try {
                    manager.close();
                } catch (Throwable cleanup) {
                    if (failure == null) {
                        failure = cleanup;
                    } else if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                List<FiberTask<?>> unfinished;
                synchronized (lock) {
                    unfinished = tasks.stream().filter(task -> task.worker == this).toList();
                    pending.clear();
                }
                for (FiberTask<?> task : unfinished) {
                    finish(task, null, failure == null ? new CancellationException("Fiber pool owner stopped") : failure);
                }
                running.clear();
                FiberManagers.unbind();
                synchronized (lock) {
                    liveWorkers--;
                    lock.notifyAll();
                }
            }
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
        }

        <T> void start(FiberTask<T> task) {
            Supplier<? extends Fiber<T>> factory;
            synchronized (lock) {
                factory = task.factory;
                task.factory = null;
            }
            try {
                if (isCancellationRequested(task)) {
                    finish(task, null, new CancellationException("Fiber pool task cancelled before starting"));
                    return;
                }
                Fiber<T> fiber = Objects.requireNonNull(factory.get(), "Fiber factory returned null");
                manager.schedule(fiber);
                running.put(fiber, task);
                synchronized (lock) {
                    task.fiber = fiber;
                    if (task.cancellationRequested) {
                        fiber.cancel();
                    }
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                finish(task, null, failure);
            }
        }

        void completed(Fiber<?> fiber) {
            FiberTask<?> task = running.remove(fiber);
            if (task != null) {
                completeTyped(task, fiber);
            }
        }

        @SuppressWarnings("unchecked")
        <T> void completeTyped(FiberTask<T> task, Fiber<?> fiber) {
            finish(task, (T) fiber.getResult(), fiber.getFailure());
        }
    }

    /** A notification-based ingress task. It never polls and never executes a user's factory under a lock. */
    private final class Ingress extends Fiber<Void> {
        private final Worker worker;

        Ingress(Worker worker) {
            this.worker = worker;
        }

        @Override
        public int update() {
            future = null;
            List<FiberTask<?>> batch = new ArrayList<>(64);
            CompletableFuture<Void> next;
            boolean more;
            synchronized (lock) {
                if (shutdown && worker.taskCount == 0) {
                    return nothingInternal();
                }
                if (worker.wake.isDone()) {
                    worker.wake = new CompletableFuture<>();
                }
                next = worker.wake;
                while (!worker.pending.isEmpty() && batch.size() < 64) {
                    FiberTask<?> task = worker.pending.removeFirst();
                    task.queued = false;
                    batch.add(task);
                }
                more = !worker.pending.isEmpty();
            }
            if (more) {
                next.complete(null);
            }
            for (FiberTask<?> task : batch) {
                worker.start(task);
            }
            state = 0;
            return awaitFor(next);
        }
    }
}
