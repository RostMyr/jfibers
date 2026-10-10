package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Live heap/RSS while parked, then a completion burst with 25% source cancellation. */
public final class MassWaitingProbe {
    private MassWaitingProbe() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioProbe.Backend backend = ScenarioProbe.Backend.valueOf(args[0]);
        int count = Integer.parseInt(args[1]);
        int idleMillis = Integer.parseInt(args[2]);
        if (count < 1 || count > 200_000 || idleMillis < 1 || backend == ScenarioProbe.Backend.JFIBERS_4) {
            throw new IllegalArgumentException("Invalid waiting probe arguments");
        }
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int iteration = 0; iteration < 20; iteration++) {
                try (Batch warmup = new Batch(backend, 512, virtual)) {
                    warmup.awaitEntered();
                    warmup.release();
                    warmup.awaitFinished();
                }
            }
            long heapBefore = liveHeap();
            long rssBefore = rss();
            long allocationBefore = ScenarioProbe.THREADS.getTotalThreadAllocatedBytes();
            long gcBefore = ScenarioProbe.gcCount(), gcMillisBefore = ScenarioProbe.gcMillis();
            long setupStart = System.nanoTime();
            try (Batch batch = new Batch(backend, count, virtual)) {
                batch.awaitEntered();
                long setupNanos = System.nanoTime() - setupStart;
                long heapWaiting = liveHeap();
                long rssWaiting = rss();
                long allocatedWaiting = ScenarioProbe.THREADS.getTotalThreadAllocatedBytes() - allocationBefore;
                long cpuStart = ScenarioProbe.OS.getProcessCpuTime();
                long idleStart = System.nanoTime();
                Thread.sleep(idleMillis);
                long idleNanos = System.nanoTime() - idleStart;
                long cpuNanos = ScenarioProbe.OS.getProcessCpuTime() - cpuStart;
                long burstStart = System.nanoTime();
                batch.release();
                long released = System.nanoTime();
                batch.awaitFinished();
                long finished = System.nanoTime();
                long[] success = new long[count], cancel = new long[count];
                int successful = 0, cancelled = 0;
                for (int id = 0; id < count; id++) {
                    Sample sample = batch.samples.get(id);
                    boolean expectedCancellation = id % 4 == 0;
                    if (sample.finished < sample.signalled || (expectedCancellation
                            ? !(sample.failure instanceof CancellationException)
                            : sample.failure != null || sample.value == null || sample.value != 42)) {
                        throw new IllegalStateException("Invalid completion at " + id, sample.failure);
                    }
                    if (expectedCancellation) {
                        cancel[cancelled++] = sample.finished - sample.signalled;
                    } else {
                        success[successful++] = sample.finished - sample.signalled;
                    }
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("backend", backend.name());
                result.put("scenario", "MASS_WAITING");
                result.put("tasks", count);
                result.put("successful", successful);
                result.put("cancelled", cancelled);
                result.put("errors", 0);
                result.put("heap_baseline_bytes", heapBefore);
                result.put("heap_waiting_bytes", heapWaiting);
                result.put("heap_delta_bytes", heapWaiting - heapBefore);
                result.put("rss_baseline_bytes", rssBefore);
                result.put("rss_waiting_bytes", rssWaiting);
                result.put("rss_delta_bytes", rssWaiting < 0 || rssBefore < 0 ? -1 : rssWaiting - rssBefore);
                result.put("allocated_to_waiting_bytes", allocatedWaiting);
                result.put("setup_ms", setupNanos / 1e6);
                result.put("idle_cpu_cores", (double) cpuNanos / idleNanos);
                result.put("release_loop_ms", (released - burstStart) / 1e6);
                result.put("burst_and_drain_ms", (finished - burstStart) / 1e6);
                result.put("success_signal_to_finish", ScenarioProbe.distribution(success, successful));
                result.put("cancel_signal_to_finish", ScenarioProbe.distribution(cancel, cancelled));
                result.put("gc_count_including_explicit", ScenarioProbe.gcCount() - gcBefore);
                result.put("gc_millis_including_explicit", ScenarioProbe.gcMillis() - gcMillisBefore);
                System.out.println(HttpSupport.json(result));
            }
        }
    }

    static final class Sample {
        final CompletableFuture<Integer> input = new CompletableFuture<>();
        final CountDownLatch entered;
        long signalled, finished;
        Integer value;
        Throwable failure;

        Sample(CountDownLatch entered) {
            this.entered = entered;
        }

        void complete(Integer answer, Throwable caught) {
            value = answer;
            failure = ScenarioProbe.unwrap(caught);
            finished = System.nanoTime();
        }
    }

    static final class Batch implements AutoCloseable {
        final List<Sample> samples;
        final List<Future<?>> handles = new ArrayList<>();
        final CountDownLatch entered;
        final CompletableFuture<Void> fibersFinished = new CompletableFuture<>();
        final Thread owner;

        Batch(ScenarioProbe.Backend backend, int count, ExecutorService virtual) {
            entered = new CountDownLatch(count);
            samples = new ArrayList<>(count);
            for (int id = 0; id < count; id++) {
                samples.add(new Sample(entered));
            }
            if (backend == ScenarioProbe.Backend.JFIBERS) {
                owner = Thread.ofPlatform().daemon(true).name("mass-waiting-fibers").start(() -> {
                    try (FiberManager manager = new FiberManager()) {
                        List<Fiber<Void>> fibers = new ArrayList<>(count);
                        for (Sample sample : samples) {
                            Fiber<Void> fiber = ScenarioFiberWorkload.waiting(sample);
                            fibers.add(fiber);
                            manager.schedule(fiber);
                        }
                        manager.run();
                        for (Fiber<Void> fiber : fibers) {
                            if (!fiber.isDone() || fiber.getFailure() != null) {
                                throw new IllegalStateException("Waiting fiber failed", fiber.getFailure());
                            }
                        }
                        fibersFinished.complete(null);
                    } catch (Throwable failure) {
                        fibersFinished.completeExceptionally(failure);
                    }
                });
            } else {
                owner = null;
                for (Sample sample : samples) {
                    if (backend == ScenarioProbe.Backend.VIRTUAL_THREADS) {
                        handles.add(virtual.submit(() -> {
                            sample.entered.countDown();
                            try {
                                sample.complete(sample.input.get() + 1, null);
                            } catch (Exception failure) {
                                sample.complete(null, failure);
                            }
                        }));
                    } else {
                        handles.add(sample.input.handle((value, failure) -> {
                            sample.complete(value == null ? null : value + 1, failure);
                            return null;
                        }));
                        entered.countDown();
                    }
                }
            }
        }

        void awaitEntered() throws Exception {
            if (!entered.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Tasks did not reach their waits");
            }
        }

        void release() {
            for (int id = 0; id < samples.size(); id++) {
                Sample sample = samples.get(id);
                sample.signalled = System.nanoTime();
                if (id % 4 == 0) {
                    sample.input.cancel(false);
                } else {
                    sample.input.complete(41);
                }
            }
        }

        void awaitFinished() throws Exception {
            if (owner != null) {
                fibersFinished.get(60, TimeUnit.SECONDS);
                owner.join(30_000);
                if (owner.isAlive()) {
                    throw new IllegalStateException("Scheduler did not exit");
                }
            } else {
                for (Future<?> handle : handles) {
                    handle.get(60, TimeUnit.SECONDS);
                }
            }
        }

        @Override
        public void close() throws Exception {
            for (Sample sample : samples) {
                sample.input.cancel(false);
            }
            awaitFinished();
        }
    }

    static long liveHeap() throws InterruptedException {
        System.gc();
        Thread.sleep(150);
        System.gc();
        Thread.sleep(150);
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static long rss() throws Exception {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return -1;
        }
        for (String line : Files.readAllLines(status)) {
            if (line.startsWith("VmRSS:")) {
                return Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
            }
        }
        return -1;
    }
}
