package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/** Run each backend and task count in a fresh JVM; see run.py for the full protocol. */
public class WaitingProbe {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    enum Backend {
        JFIBERS, VIRTUAL_THREADS, COMPLETABLE_FUTURE
    }

    public static void main(String[] args) throws Exception {
        Backend backend = Backend.valueOf(args[0]);
        int count = Integer.parseInt(args[1]);
        long idleMillis = Long.parseLong(args[2]);
        if (count < 1 || idleMillis < 1) {
            throw new IllegalArgumentException("Positive task count and idle interval are required");
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            warmup(backend, executor);
            long baseline = liveHeap();
            try (PendingBatch batch = new PendingBatch(backend, count, executor)) {
                batch.awaitEntered();
                long waitingHeap = liveHeap();
                long cpuBefore = OS.getProcessCpuTime();
                long wallBefore = System.nanoTime();
                Thread.sleep(idleMillis);
                long wallNanos = System.nanoTime() - wallBefore;
                long cpuNanos = OS.getProcessCpuTime() - cpuBefore;

                long cancelStart = System.nanoTime();
                batch.cancelInputs();
                batch.awaitResults(true);
                long cancellationNanos = System.nanoTime() - cancelStart;

                System.out.printf(Locale.ROOT,
                    "{\"backend\":\"%s\",\"tasks\":%d,\"heap_baseline_bytes\":%d,\"heap_waiting_bytes\":%d,"
                        + "\"heap_delta_bytes\":%d,\"idle_wall_ms\":%.3f,\"idle_cpu_ms\":%.3f,"
                        + "\"idle_cpu_cores\":%.6f,\"cancel_source_and_drain_ms\":%.3f}%n",
                    backend, count, baseline, waitingHeap, waitingHeap - baseline,
                    wallNanos / 1_000_000.0, cpuNanos / 1_000_000.0, (double) cpuNanos / wallNanos,
                    cancellationNanos / 1_000_000.0);
            }
        }
    }

    private static void warmup(Backend backend, ExecutorService executor) throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            try (PendingBatch batch = new PendingBatch(backend, 256, executor)) {
                batch.awaitEntered();
                Thread.sleep(2);
                boolean cancel = iteration % 2 != 0;
                if (cancel) {
                    batch.cancelInputs();
                } else {
                    batch.inputs.forEach(input -> input.complete(41));
                }
                batch.awaitResults(cancel);
            }
        }
    }

    private static long liveHeap() throws InterruptedException {
        // Full collections reduce floating garbage; the delta is an estimate of retained Java heap.
        System.gc();
        Thread.sleep(100);
        System.gc();
        Thread.sleep(100);
        return MEMORY.getHeapMemoryUsage().getUsed();
    }

    private static class PendingBatch implements AutoCloseable {
        private final Backend backend;
        private final List<CompletableFuture<Integer>> inputs;
        private final List<Fiber<Integer>> fibers;
        private final List<Future<Integer>> threads;
        private final List<CompletableFuture<Integer>> stages;
        private final CompletableFuture<Void> fiberEntered = new CompletableFuture<>();
        private final CompletableFuture<Void> fiberFinished = new CompletableFuture<>();
        private final CountDownLatch threadEntered;
        private final Thread fiberRunner;

        PendingBatch(Backend backend, int count, ExecutorService executor) {
            this.backend = backend;
            inputs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                inputs.add(new CompletableFuture<>());
            }
            fibers = backend == Backend.JFIBERS ? new ArrayList<>(count) : List.of();
            threads = backend == Backend.VIRTUAL_THREADS ? new ArrayList<>(count) : List.of();
            stages = backend == Backend.COMPLETABLE_FUTURE ? new ArrayList<>(count) : List.of();
            threadEntered = new CountDownLatch(backend == Backend.VIRTUAL_THREADS ? count : 0);
            if (backend == Backend.JFIBERS) {
                fiberRunner = Thread.ofPlatform().daemon(true).start(this::runFibers);
            } else {
                fiberRunner = null;
                for (CompletableFuture<Integer> input : inputs) {
                    if (backend == Backend.VIRTUAL_THREADS) {
                        threads.add(executor.submit(() -> {
                            threadEntered.countDown();
                            return input.get() + 1;
                        }));
                    } else {
                        stages.add(input.thenApply(value -> value + 1));
                    }
                }
            }
        }

        private void runFibers() {
            try {
                FiberManager manager = new FiberManager();
                FiberWorkload workload = new FiberWorkload();
                for (CompletableFuture<Integer> input : inputs) {
                    Fiber<Integer> task = workload.increment(input);
                    fibers.add(task);
                    manager.schedule(task);
                }
                manager.schedule(new Fiber<Void>() {
                    @Override
                    public int update() {
                        // Each task has executed awaitFor by the time this sentinel is reached.
                        fiberEntered.complete(null);
                        return -1;
                    }
                });
                manager.run();
                fiberFinished.complete(null);
            } catch (Throwable failure) {
                fiberEntered.completeExceptionally(failure);
                fiberFinished.completeExceptionally(failure);
            }
        }

        void awaitEntered() throws Exception {
            if (backend == Backend.JFIBERS) {
                fiberEntered.get(30, TimeUnit.SECONDS);
            } else if (!threadEntered.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Virtual threads did not enter their tasks");
            }
        }

        void cancelInputs() {
            inputs.forEach(input -> input.cancel(false));
        }

        void awaitResults(boolean cancelled) throws Exception {
            if (backend == Backend.JFIBERS) {
                fiberFinished.get(30, TimeUnit.SECONDS);
                for (Fiber<Integer> fiber : fibers) {
                    check(fiber.getResult(), fiber.getException(), cancelled);
                }
            } else if (backend == Backend.VIRTUAL_THREADS) {
                for (Future<Integer> task : threads) {
                    checkFuture(task, cancelled);
                }
            } else {
                for (CompletableFuture<Integer> task : stages) {
                    checkFuture(task, cancelled);
                }
            }
        }

        private void checkFuture(Future<Integer> task, boolean cancelled) throws Exception {
            Integer value = null;
            Throwable failure = null;
            try {
                value = task.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failure = e.getCause();
            } catch (CancellationException e) {
                failure = e;
            }
            check(value, failure, cancelled);
        }

        private void check(Integer value, Throwable failure, boolean cancelled) {
            if (cancelled) {
                if (!(failure instanceof CancellationException)) {
                    throw new IllegalStateException("Expected cancellation", failure);
                }
            } else if (failure != null || value == null || value != 42) {
                throw new IllegalStateException("Incorrect task result: " + value, failure);
            }
        }

        @Override
        public void close() throws InterruptedException {
            cancelInputs();
            if (fiberRunner != null) {
                fiberRunner.join(30_000);
                if (fiberRunner.isAlive()) {
                    throw new IllegalStateException("Fiber runner did not terminate");
                }
            }
        }
    }
}
