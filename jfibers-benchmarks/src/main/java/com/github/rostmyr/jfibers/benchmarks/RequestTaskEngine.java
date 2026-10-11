package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.FiberFutures;
import com.github.rostmyr.jfibers.FiberPool;
import com.github.rostmyr.jfibers.FiberTask;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
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

    static final class AdmissionRejectedException extends RejectedExecutionException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        AdmissionRejectedException(String reason, Throwable cause) {
            super("Request admission exhausted: " + reason, cause);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

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
    private final int heavyCapacity;
    private final int computeThreads;
    private final int dependencyDelayMillis;
    private final FiberPool pool;
    private final ExecutorService virtualThreads;
    private final Semaphore permits;
    private final ExecutorService compute;
    private final RequestDependencyClient dependency;
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
    // Admission and terminal work accounting use the engine lock; no shared counter on each CPU step.
    private final long[] cpuSteps = new long[4];
    private final long[] cpuIterations = new long[4];
    private long rejectedHeavy;
    private long rejectedGlobal;
    private long rejectedClosed;
    private int activeHeavy;
    private int peakHeavy;
    private int peakRoots;
    private int publishing;
    private boolean closed;
    private volatile Map<String, Object> firstError;

    RequestTaskEngine(Backend backend, int capacity, int heavyIterations) {
        this(backend, capacity, heavyIterations, 0);
    }

    RequestTaskEngine(Backend backend, int capacity, int heavyIterations, int heavyCapacity) {
        this(backend, capacity, heavyIterations, heavyCapacity, 3, null, 1);
    }

    RequestTaskEngine(Backend backend, int capacity, int heavyIterations, int heavyCapacity,
                      int computeThreads, URI dependencyBase, int dependencyDelayMillis) {
        if (capacity < 1 || heavyIterations < 1 || heavyCapacity < 0 || computeThreads < 1
            || dependencyDelayMillis < 0) {
            throw new IllegalArgumentException("Capacities/iterations/threads must be positive; heavy cap/delay nonnegative");
        }
        this.backend = java.util.Objects.requireNonNull(backend);
        this.capacity = capacity;
        this.heavyIterations = heavyIterations;
        this.heavyCapacity = heavyCapacity;
        this.computeThreads = computeThreads;
        this.dependencyDelayMillis = dependencyDelayMillis;
        dependency = dependencyBase == null ? null : new RequestDependencyClient(dependencyBase, capacity);
        timer.setRemoveOnCancelPolicy(true);
        pool = backend == Backend.VIRTUAL_THREADS ? null
            : new FiberPool(backend == Backend.FIBER_POOL_4 ? 4 : 1, capacity);
        virtualThreads = pool == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        permits = pool == null ? new Semaphore(capacity) : null;
        compute = backend == Backend.FIBER_POOL_OFFLOAD
            ? FiberFutures.newComputeExecutor(computeThreads, capacity) : null;
    }

    Submission submit(long id, boolean hold, int deadlineMillis, int cleanupMillis) {
        if (deadlineMillis < 0 || cleanupMillis < 0) {
            throw new IllegalArgumentException("Deadlines and cleanup delays must not be negative");
        }
        Request request = new Request(id, hold, deadlineMillis, cleanupMillis);
        synchronized (lock) {
            if (closed) {
                rejected.incrementAndGet();
                rejectedClosed++;
                throw new RejectedExecutionException("Request engine is closed");
            }
            // The whole heavy request owns a slot across every wait, CPU step, and cleanup.
            // This gate has precedence over global admission when both limits are full.
            if (request.heavy) {
                if (heavyCapacity > 0 && activeHeavy == heavyCapacity) {
                    rejected.incrementAndGet();
                    rejectedHeavy++;
                    throw new AdmissionRejectedException("heavy", null);
                }
                activeHeavy++;
            }
            int reservedHeavy = activeHeavy;
            roots.add(request);
            try {
                if (pool != null) {
                    FiberTask<Void> handle = pool.submit(() -> RequestFiberWorkload.run(request));
                    request.install(handle);
                    accepted.incrementAndGet();
                    peakHeavy = Math.max(peakHeavy, reservedHeavy);
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
                    peakHeavy = Math.max(peakHeavy, reservedHeavy);
                    peakRoots = Math.max(peakRoots, capacity - permits.availablePermits());
                }
            } catch (RejectedExecutionException failure) {
                rollback(request);
                rejected.incrementAndGet();
                rejectedGlobal++;
                throw new AdmissionRejectedException("global", failure);
            } catch (RuntimeException | Error failure) {
                // Unexpected executor/factory setup failures are not capacity rejections.
                rollback(request);
                throw failure;
            }
            request.armDeadline();
        }
        return new Submission(request);
    }

    private void rollback(Request request) {
        if (roots.remove(request) && request.heavy) {
            activeHeavy--;
        }
    }

    static long expected(long id, int heavyIterations) {
        int iterations = heavy(id) ? heavyIterations : 1_000;
        long checksum = 0;
        for (int step = 0; step < 3; step++) {
            checksum += ScenarioProbe.cpuWork(42 ^ step, iterations);
        }
        return checksum;
    }

    static boolean heavy(long id) {
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
            CompletableFuture<Void> cleanup = request.beginCleanup(failure);
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
        FailureDetails failureDetails = result.status() == 500 ? request.takeFailureDetails() : null;
        synchronized (lock) {
            if (!roots.remove(request)) {
                return;
            }
            if (request.heavy) {
                activeHeavy--;
            }
            publishing++;
            completed.incrementAndGet();
            int outcome;
            switch (result.status()) {
                case 200 -> { success.incrementAndGet(); outcome = 0; }
                case 409 -> { cancelled.incrementAndGet(); outcome = 1; }
                case 504 -> { timedOut.incrementAndGet(); outcome = 2; }
                default -> { errors.incrementAndGet(); outcome = 3; }
            }
            if (firstError == null && failureDetails != null) {
                firstError = failureDetails.describe();
            }
            // A compute future's cancellation is insufficient: result() is reached only after
            // cleanup awaits its actual-exit gate, publishing these per-request writes.
            cpuSteps[outcome] += request.cpuSteps;
            cpuIterations[outcome] += request.cpuIterations;
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
            if (firstError != null) {
                result.put("first_error", firstError);
            }
            result.put("cleanup_started", cleanupStarted.get());
            result.put("rejected", rejected.get());
            result.put("heavy_capacity", heavyCapacity);
            result.put("active_heavy", activeHeavy);
            result.put("peak_heavy", peakHeavy);
            result.put("rejected_heavy", rejectedHeavy);
            result.put("rejected_global", rejectedGlobal);
            result.put("rejected_closed", rejectedClosed);
            String[] outcomes = {"success", "cancelled", "timed_out", "errors"};
            long totalSteps = 0;
            long totalIterations = 0;
            for (int i = 0; i < outcomes.length; i++) {
                result.put("cpu_steps_" + outcomes[i], cpuSteps[i]);
                result.put("cpu_iterations_" + outcomes[i], cpuIterations[i]);
                totalSteps += cpuSteps[i];
                totalIterations += cpuIterations[i];
            }
            result.put("cpu_steps", totalSteps);
            result.put("cpu_iterations", totalIterations);
            result.put("compute_threads", computeThreads);
            result.put("compute_executor_threads", compute == null ? 0 : computeThreads);
            result.put("wait_mode", dependency == null ? "timer" : "http");
            result.put("dependency_delay_ms", dependencyDelayMillis);
            if (dependency != null) {
                result.putAll(dependency.snapshot());
            } else {
                for (String counter : new String[] {"started", "completed", "succeeded", "failed", "active", "peak",
                    "executor_queue", "executor_active"}) {
                    result.put("downstream_" + counter, 0L);
                }
            }
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
                if (roots.isEmpty() && publishing == 0 && activeHeavy == 0
                    && pendingSources.sum() == 0 && pendingTimers.sum() == 0
                    && activeCompute.sum() == 0 && timer.getQueue().isEmpty()
                    && (dependency == null || dependency.isQuiescent())
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
        for (String field : new String[] {"active_roots", "active_heavy", "pending_completions", "pending_sources", "pending_timers",
            "active_compute", "compute_queue_size", "timer_queue_size", "downstream_active",
            "downstream_executor_queue", "downstream_executor_active"}) {
            if (((Number) values.get(field)).longValue() != 0) {
                throw new IllegalStateException("Request engine did not drain: " + values);
            }
        }
        if (!values.get("accepted").equals(values.get("completed"))) {
            throw new IllegalStateException("Lost request completions: " + values);
        }
        if (!values.get("downstream_started").equals(values.get("downstream_completed"))) {
            throw new IllegalStateException("Lost dependency completions: " + values);
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
        if (dependency != null) {
            dependency.close();
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
        final boolean heavy;
        final CompletableFuture<Result> completion = new CompletableFuture<>();
        final AtomicLong computeQueue = new AtomicLong();
        private FiberTask<Void> handle;
        private TimerTicket deadline;
        private Source source;
        private CompletableFuture<Void> sourceExit;
        private Computation computation;
        private CompletableFuture<Void> cleanup;
        private int decision;
        private long started;
        private long cleanupStarted;
        private long bodyEnded;
        private long checksum;
        // One CPU loop at a time per request. Inline completion or the offload exit gate
        // publishes the final values before finish aggregates them under the engine lock.
        private long cpuSteps;
        private long cpuIterations;
        private int nextStep;
        private boolean finished;
        private Throwable bodyFailure;
        private FailureDetails failureDetails;

        Request(long id, boolean hold, int deadlineMillis, int cleanupMillis) {
            this.id = id;
            this.hold = hold;
            this.deadlineMillis = deadlineMillis;
            this.cleanupMillis = cleanupMillis;
            heavy = heavy(id);
            offload = backend == Backend.FIBER_POOL_OFFLOAD && heavy;
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
            source = hold || dependency == null
                ? new Source(hold ? -1 : TimeUnit.MILLISECONDS.toNanos(dependencyDelayMillis))
                : new Source(id, nextStep++);
            return source.future;
        }

        long computeInline(int step) {
            int iterations = heavy ? heavyIterations : 1_000;
            long value = ScenarioProbe.cpuWork(42 ^ step, iterations);
            cpuSteps++;
            cpuIterations += iterations;
            return value;
        }

        synchronized CompletableFuture<Long> computeAsync(int step) {
            checkRunning();
            computation = new Computation(this, step);
            return computation.result;
        }

        synchronized CompletableFuture<Void> beginCleanup(Throwable failure) {
            if (cleanup != null) {
                return cleanup;
            }
            bodyFailure = failure;
            cleanupStarted = System.nanoTime();
            RequestTaskEngine.this.cleanupStarted.incrementAndGet();
            CompletableFuture<Void> exited = CompletableFuture.completedFuture(null);
            if (source != null) {
                source.cancel();
                exited = source.exited;
                sourceExit = exited;
                source = null;
            }
            if (computation != null) {
                computation.cancel();
                exited = exited.isDone() ? computation.exited : CompletableFuture.allOf(exited, computation.exited);
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
            int decisionBeforeResult = decision;
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
            if (decision == 500 && firstError == null) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("id", id);
                state.put("heavy", heavy);
                state.put("decision_before_result", decisionBeforeResult);
                state.put("observed_nanos", System.nanoTime());
                state.put("submitted_nanos", submitted);
                state.put("started_nanos", started);
                state.put("cleanup_started_nanos", cleanupStarted);
                state.put("body_ended_nanos", bodyEnded);
                state.put("source_present", source != null);
                state.put("source_done", source != null && source.future.isDone());
                CompletableFuture<Void> exit = source == null ? sourceExit : source.exited;
                state.put("source_exit_present", exit != null);
                state.put("source_exit_done", exit != null && exit.isDone());
                state.put("cleanup_present", cleanup != null);
                state.put("cleanup_done", cleanup != null && cleanup.isDone());
                state.put("compute_present", computation != null);
                state.put("compute_done", computation != null && computation.result.isDone());
                state.put("compute_exit_done", computation != null && computation.exited.isDone());
                failureDetails = new FailureDetails(state, failure, bodyFailure);
            }
            bodyFailure = null;
            sourceExit = null;
            long end = bodyEnded == 0 ? System.nanoTime() : bodyEnded;
            return new Result(decision, id, checksum, started == 0 ? -1 : started - submitted,
                started == 0 ? -1 : end - started,
                cleanupStarted == 0 ? 0 : end - cleanupStarted, computeQueue.get());
        }

        synchronized FailureDetails takeFailureDetails() {
            FailureDetails details = failureDetails;
            failureDetails = null;
            return details;
        }
    }

    /** Only unexpected failures allocate diagnostics; retain at most 16,384 characters of stack text. */
    private record FailureDetails(Map<String, Object> state, Throwable nativeFailure, Throwable bodyFailure) {
        Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>(state);
            Throwable failure = nativeFailure == null ? bodyFailure : nativeFailure;
            result.put("failure_class", failure == null ? "<unavailable>" : failure.getClass().getName());
            String message = failure == null || failure.getMessage() == null ? "" : failure.getMessage();
            result.put("failure_message", message.substring(0, Math.min(message.length(), 1024)));
            StackWriter stack = new StackWriter();
            PrintWriter output = new PrintWriter(stack);
            if (failure != null) {
                failure.printStackTrace(output);
            }
            if (bodyFailure != null && bodyFailure != failure) {
                output.println("Body failure before cleanup:");
                bodyFailure.printStackTrace(output);
            }
            output.flush();
            result.put("failure_stack", stack.text.toString());
            result.put("stack_truncated", stack.truncated);
            return Map.copyOf(result);
        }
    }

    private static final class StackWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        @Override
        public void write(char[] characters, int offset, int length) {
            int copied = Math.min(length, 16_384 - text.length());
            text.append(characters, offset, copied);
            truncated |= copied < length;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
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
        private final CompletableFuture<Integer> future;
        private final CompletableFuture<Void> exited;
        private final TimerTicket ticket;

        Source(long delayNanos) {
            future = new CompletableFuture<>();
            exited = new CompletableFuture<>();
            ticket = new TimerTicket();
            pendingSources.increment();
            future.whenComplete((ignored, failure) -> {
                pendingSources.decrement();
                exited.complete(null);
            });
            if (delayNanos >= 0) {
                ticket.arm(delayNanos, () -> future.complete(42));
            }
        }

        Source(long id, int step) {
            ticket = null;
            pendingSources.increment();
            RequestDependencyClient.Call call;
            try {
                call = dependency.start(id, step, dependencyDelayMillis);
            } catch (RuntimeException | Error failure) {
                pendingSources.decrement();
                throw failure;
            }
            future = call.value;
            exited = call.exited;
            exited.whenComplete((ignored, failure) -> pendingSources.decrement());
        }

        void cancel() {
            if (ticket != null) {
                ticket.cancel();
            }
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
