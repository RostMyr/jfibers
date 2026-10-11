package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.FiberFutures;
import com.github.rostmyr.jfibers.FiberPool;
import com.github.rostmyr.jfibers.FiberTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** One independently admitted root per request, shared by the HTTP benchmark backends. */
final class RequestTaskEngine implements AutoCloseable {
    enum Backend { FIBER_POOL_1, FIBER_POOL_4, FIBER_POOL_OFFLOAD, VIRTUAL_THREADS }

    record Result(int status, long id, long checksum, long queueNanos, long serviceNanos,
                  long cleanupNanos, long computeQueueNanos) { }

    static final class Submission {
        private final Request request;

        Submission(Request request) {
            this.request = request;
        }

        CompletionStage<Result> completion() {
            return request.completion.minimalCompletionStage();
        }

        boolean cancel() {
            return request.stop(409);
        }
    }

    private final Object lock = new Object();
    private final Backend backend;
    private final int capacity;
    private final int heavyIterations;
    private final FiberPool pool;
    private final ExecutorService virtualThreads;
    private final Semaphore permits;
    private final ExecutorService compute;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(4);
    private final Set<Request> roots = new HashSet<>();
    private final LongAdder pendingSources = new LongAdder();
    private final LongAdder pendingTimers = new LongAdder();
    private final LongAdder activeCompute = new LongAdder();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong cleanupStarted = new AtomicLong();
    private int peakRoots;
    private int publishing;
    private boolean closed;

    RequestTaskEngine(Backend backend, int capacity, int heavyIterations) {
        if (capacity < 1 || heavyIterations < 1) {
            throw new IllegalArgumentException("Capacity and CPU iterations must be positive");
        }
        this.backend = java.util.Objects.requireNonNull(backend);
        this.capacity = capacity;
        this.heavyIterations = heavyIterations;
        timer.setRemoveOnCancelPolicy(true);
        pool = backend == Backend.VIRTUAL_THREADS ? null
            : new FiberPool(backend == Backend.FIBER_POOL_4 ? 4 : 1, capacity);
        virtualThreads = pool == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        permits = pool == null ? new Semaphore(capacity) : null;
        compute = backend == Backend.FIBER_POOL_OFFLOAD
            ? FiberFutures.newComputeExecutor(3, capacity) : null;
    }

    Submission submit(long id, boolean hold, int deadlineMillis, int cleanupMillis) {
        if (deadlineMillis < 0 || cleanupMillis < 0) {
            throw new IllegalArgumentException("Deadlines and cleanup delays must not be negative");
        }
        Request request = new Request(id, hold, deadlineMillis, cleanupMillis);
        synchronized (lock) {
            if (closed) {
                rejected.incrementAndGet();
                throw new RejectedExecutionException("Request engine is closed");
            }
            roots.add(request);
            try {
                if (pool != null) {
                    FiberTask<Void> handle = pool.submit(() -> RequestFiberWorkload.run(request));
                    request.install(handle);
                    accepted.incrementAndGet();
                    peakRoots = Math.max(peakRoots, pool.getTaskCount());
                    handle.completion().whenComplete((ignored, failure) -> finish(request, failure));
                } else {
                    if (!permits.tryAcquire()) {
                        throw new RejectedExecutionException("Request capacity exhausted");
                    }
                    try {
                        virtualThreads.execute(() -> {
                            Throwable failure = null;
                            try {
                                runVirtual(request);
                            } catch (Throwable caught) {
                                failure = caught;
                            } finally {
                                permits.release();
                                finish(request, failure);
                            }
                        });
                    } catch (RuntimeException | Error failure) {
                        permits.release();
                        throw failure;
                    }
                    accepted.incrementAndGet();
                    peakRoots = Math.max(peakRoots, capacity - permits.availablePermits());
                }
            } catch (RuntimeException | Error failure) {
                roots.remove(request);
                rejected.incrementAndGet();
                throw failure;
            }
            request.armDeadline();
        }
        return new Submission(request);
    }

    static long expected(long id, int heavyIterations) {
        int iterations = heavy(id) ? heavyIterations : 1_000;
        long checksum = 0;
        for (int step = 0; step < 3; step++) {
            checksum += ScenarioProbe.cpuWork(42 ^ step, iterations);
        }
        return checksum;
    }

    private static boolean heavy(long id) {
        return Math.floorMod(id * 37, 100) < 10;
    }

    private void runVirtual(Request request) throws Exception {
        if (!request.start()) {
            return;
        }
        long checksum = 0;
        Throwable failure = null;
        try {
            for (int step = 0; step < 3; step++) {
                request.checkRunning();
                request.openStep().get();
                request.checkRunning();
                checksum += request.computeInline(step);
            }
            request.checkRunning();
        } catch (Exception caught) {
            failure = caught;
        } finally {
            CompletableFuture<Void> cleanup = request.beginCleanup();
            boolean interrupted = false;
            while (true) {
                try {
                    cleanup.get();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            request.bodyFinished(checksum, failure);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void finish(Request request, Throwable failure) {
        Result result = request.result(failure);
        synchronized (lock) {
            if (!roots.remove(request)) {
                return;
            }
            publishing++;
            completed.incrementAndGet();
            switch (result.status()) {
                case 200 -> success.incrementAndGet();
                case 409 -> cancelled.incrementAndGet();
                case 504 -> timedOut.incrementAndGet();
                default -> errors.incrementAndGet();
            }
        }
        try {
            request.completion.complete(result);
        } finally {
            synchronized (lock) {
                publishing--;
                lock.notifyAll();
            }
        }
    }

    Map<String, Object> snapshot() {
        synchronized (lock) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accepted", accepted.get());
            result.put("completed", completed.get());
            result.put("success", success.get());
            result.put("cancelled", cancelled.get());
            result.put("timed_out", timedOut.get());
            result.put("errors", errors.get());
            result.put("cleanup_started", cleanupStarted.get());
            result.put("rejected", rejected.get());
            result.put("active_roots", pool == null ? capacity - permits.availablePermits() : pool.getTaskCount());
            result.put("pending_completions", roots.size() + publishing);
            result.put("peak_roots", peakRoots);
            result.put("pending_sources", pendingSources.sum());
            result.put("pending_timers", pendingTimers.sum());
            result.put("active_compute", activeCompute.sum());
            result.put("compute_queue_size", compute instanceof ThreadPoolExecutor executor
                ? executor.getQueue().size() : 0);
            result.put("timer_queue_size", timer.getQueue().size());
            return result;
        }
    }

    boolean awaitQuiescent(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            synchronized (lock) {
                if (roots.isEmpty() && publishing == 0 && pendingSources.sum() == 0 && pendingTimers.sum() == 0
                    && activeCompute.sum() == 0 && timer.getQueue().isEmpty()
                    && (!(compute instanceof ThreadPoolExecutor executor) || executor.getQueue().isEmpty())) {
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, Math.min(remaining, 1_000_000));
            }
        }
    }

    void assertQuiescent() {
        Map<String, Object> values = snapshot();
        for (String field : new String[] {"active_roots", "pending_completions", "pending_sources", "pending_timers",
            "active_compute", "compute_queue_size", "timer_queue_size"}) {
            if (((Number) values.get(field)).longValue() != 0) {
                throw new IllegalStateException("Request engine did not drain: " + values);
            }
        }
        if (!values.get("accepted").equals(values.get("completed"))) {
            throw new IllegalStateException("Lost request completions: " + values);
        }
    }

    @Override
    public void close() {
        ArrayList<Request> pending;
        synchronized (lock) {
            closed = true;
            pending = new ArrayList<>(roots);
        }
        for (Request request : pending) {
            request.stop(409);
        }
        // Timers and compute stay alive until each accepted root has finished its finally block.
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(Duration.ofSeconds(30))) {
                    throw new IllegalStateException("Fiber request cleanup did not finish");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted draining request fibers", interrupted);
            }
        } else {
            virtualThreads.close();
        }
        if (compute != null) {
            compute.close();
        }
        timer.shutdown();
        try {
            if (!timer.awaitTermination(30, TimeUnit.SECONDS) || !awaitQuiescent(Duration.ofSeconds(5))) {
                throw new IllegalStateException("Request resources did not drain: " + snapshot());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted draining request resources", interrupted);
        }
        assertQuiescent();
    }

    final class Request {
        final long id;
        final long submitted = System.nanoTime();
        final boolean hold;
        final int deadlineMillis;
        final int cleanupMillis;
        final boolean offload;
        final CompletableFuture<Result> completion = new CompletableFuture<>();
        final AtomicLong computeQueue = new AtomicLong();
        private FiberTask<Void> handle;
        private TimerTicket deadline;
        private Source source;
        private Computation computation;
        private CompletableFuture<Void> cleanup;
        private int decision;
        private long started;
        private long cleanupStarted;
        private long bodyEnded;
        private long checksum;
        private boolean finished;

        Request(long id, boolean hold, int deadlineMillis, int cleanupMillis) {
            this.id = id;
            this.hold = hold;
            this.deadlineMillis = deadlineMillis;
            this.cleanupMillis = cleanupMillis;
            offload = backend == Backend.FIBER_POOL_OFFLOAD && heavy(id);
        }

        synchronized void install(FiberTask<Void> installed) {
            handle = installed;
            if (decision != 0) {
                handle.cancel();
            }
        }

        synchronized void armDeadline() {
            if (!finished && decision == 0 && deadlineMillis > 0) {
                deadline = new TimerTicket();
                deadline.arm(Math.max(0, submitted + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
                    - System.nanoTime()), () -> stop(504));
            }
        }

        synchronized boolean start() {
            if (decision == 0 && expired()) {
                stop(504);
            }
            if (decision != 0) {
                return false;
            }
            started = System.nanoTime();
            return true;
        }

        synchronized boolean stop(int status) {
            if (decision != 0 || finished) {
                return false;
            }
            decision = expired() ? 504 : status;
            if (handle != null) {
                handle.cancel();
            }
            if (source != null) {
                source.cancel();
            }
            if (computation != null) {
                computation.cancel();
            }
            return true;
        }

        synchronized void checkRunning() {
            if (decision == 0 && expired()) {
                stop(504);
            }
            if (decision != 0) {
                throw new CancellationException("Request stopped: " + decision);
            }
        }

        private boolean expired() {
            return deadlineMillis > 0
                && System.nanoTime() - submitted >= TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        }

        synchronized CompletableFuture<Integer> openStep() {
            checkRunning();
            source = new Source(hold ? -1 : 1_000_000);
            return source.future;
        }

        long computeInline(int step) {
            return ScenarioProbe.cpuWork(42 ^ step, heavy(id) ? heavyIterations : 1_000);
        }

        synchronized CompletableFuture<Long> computeAsync(int step) {
            checkRunning();
            computation = new Computation(this, step);
            return computation.result;
        }

        synchronized CompletableFuture<Void> beginCleanup() {
            if (cleanup != null) {
                return cleanup;
            }
            cleanupStarted = System.nanoTime();
            RequestTaskEngine.this.cleanupStarted.incrementAndGet();
            if (source != null) {
                source.cancel();
                source = null;
            }
            CompletableFuture<Void> exited = CompletableFuture.completedFuture(null);
            if (computation != null) {
                computation.cancel();
                exited = computation.exited;
            }
            // Cancellation targets only body sources, never this cleanup future.
            cleanup = exited.thenCompose(ignored -> cleanupMillis == 0
                ? CompletableFuture.completedFuture(null)
                : new Source(TimeUnit.MILLISECONDS.toNanos(cleanupMillis)).future.thenApply(value -> null));
            return cleanup;
        }

        synchronized void bodyFinished(long value, Throwable failure) {
            checksum = value;
            bodyEnded = System.nanoTime();
            if (decision == 0) {
                decision = expired() ? 504 : failure == null ? 200 : 500;
            }
        }

        synchronized Result result(Throwable failure) {
            finished = true;
            if (deadline != null) {
                deadline.cancel();
            }
            if (decision == 0) {
                decision = 500;
            }
            if (decision == 200 && failure != null) {
                decision = 500;
            }
            // Unexpected cleanup failures must not be hidden behind a requested cancellation.
            if (started != 0 && bodyEnded == 0) {
                decision = 500;
            }
            long end = bodyEnded == 0 ? System.nanoTime() : bodyEnded;
            return new Result(decision, id, checksum, started == 0 ? -1 : started - submitted,
                started == 0 ? -1 : end - started,
                cleanupStarted == 0 ? 0 : end - cleanupStarted, computeQueue.get());
        }
    }

    private final class Computation {
        private final AtomicInteger state = new AtomicInteger(); // queued, running, exited
        private final CompletableFuture<Void> exited = new CompletableFuture<>();
        private final CompletableFuture<Long> result;

        Computation(Request request, int step) {
            long enqueued = System.nanoTime();
            result = FiberFutures.submit(() -> {
                if (!state.compareAndSet(0, 1)) {
                    throw new CancellationException("Compute cancelled before execution");
                }
                activeCompute.increment();
                request.computeQueue.addAndGet(System.nanoTime() - enqueued);
                try {
                    // Deliberately non-preemptible, matching inline work on every backend.
                    return request.computeInline(step);
                } finally {
                    activeCompute.decrement();
                    state.set(2);
                    exited.complete(null);
                }
            }, compute);
        }

        void cancel() {
            if (state.compareAndSet(0, 2)) {
                exited.complete(null);
            }
            result.cancel(false);
        }
    }

    private final class Source {
        private final CompletableFuture<Integer> future = new CompletableFuture<>();
        private final TimerTicket ticket = new TimerTicket();

        Source(long delayNanos) {
            pendingSources.increment();
            future.whenComplete((ignored, failure) -> pendingSources.decrement());
            if (delayNanos >= 0) {
                ticket.arm(delayNanos, () -> future.complete(42));
            }
        }

        void cancel() {
            ticket.cancel();
            future.cancel(false);
        }
    }

    /** Counts scheduled and executing callbacks, including cancellation racing with timer start. */
    private final class TimerTicket {
        private ScheduledFuture<?> future;
        private int state; // unarmed, queued, running, finished

        void arm(long delayNanos, Runnable action) {
            synchronized (this) {
                state = 1;
                pendingTimers.increment();
            }
            ScheduledFuture<?> scheduled;
            try {
                scheduled = timer.schedule(() -> {
                    synchronized (this) {
                        if (state != 1) {
                            return;
                        }
                        state = 2;
                    }
                    try {
                        action.run();
                    } finally {
                        synchronized (this) {
                            state = 3;
                            pendingTimers.decrement();
                        }
                    }
                }, delayNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                synchronized (this) {
                    state = 3;
                    pendingTimers.decrement();
                }
                throw failure;
            }
            synchronized (this) {
                future = scheduled;
                if (state == 3) {
                    scheduled.cancel(false);
                }
            }
        }

        synchronized void cancel() {
            if (state == 1) {
                state = 3;
                pendingTimers.decrement();
                if (future != null) {
                    future.cancel(false);
                }
            }
        }
    }
}
