package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import com.sun.management.OperatingSystemMXBean;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Closed-loop HTTP comparison. Run through http_run.py, which also manages the server. */
public final class HttpLoadProbe {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    enum Backend {
        JFIBERS, VIRTUAL_THREADS, COMPLETABLE_FUTURE
    }

    private HttpLoadProbe() {
    }

    public static void main(String[] args) throws Exception {
        Backend backend = Backend.valueOf(args[0]);
        URI base = URI.create(args[1]);
        int concurrency = Integer.parseInt(args[2]);
        long warmupMillis = Long.parseLong(args[3]);
        long measureMillis = Long.parseLong(args[4]);
        if (!"127.0.0.1".equals(base.getHost()) || !"http".equals(base.getScheme())) {
            throw new IllegalArgumentException("This harness only targets the local test server");
        }
        if (concurrency < 1 || concurrency > 4096 || warmupMillis < 100 || measureMillis < 100
                || warmupMillis > 300_000 || measureMillis > 300_000) {
            throw new IllegalArgumentException("Invalid concurrency or phase duration");
        }
        try (ExecutorService httpExecutor = Executors.newFixedThreadPool(4);
             ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                 .connectTimeout(Duration.ofSeconds(5)).executor(httpExecutor).build()) {
            HttpSupport.Api api = new HttpSupport.Api(client, base);
            Map<String, Object> warmup = runPhase(backend, api, virtualExecutor, concurrency, warmupMillis);
            if (((Number) warmup.get("errors")).longValue() != 0) {
                throw new IllegalStateException("Warmup failed: " + HttpSupport.json(warmup));
            }
            // Collect before, never during, the measured phase. Connections and JIT state remain warm.
            System.gc();
            Thread.sleep(100);
            System.out.println(HttpSupport.json(Map.of("ready", true, "warmup", warmup)));
            BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            if (!"measure".equals(commands.readLine())) {
                throw new IllegalStateException("Expected measurement command");
            }
            try (Recording recording = args.length > 5 ? new Recording(Configuration.getConfiguration("profile")) : null) {
                if (recording != null) {
                    recording.setDestination(Path.of(args[5]));
                    recording.start();
                }
                Map<String, Object> measurement = runPhase(backend, api, virtualExecutor, concurrency, measureMillis);
                if (recording != null) {
                    recording.stop();
                }
                System.out.println(HttpSupport.json(Map.of("result", measurement, "profiled", recording != null)));
            }
        }
    }

    private static Map<String, Object> runPhase(Backend backend, HttpSupport.Api api, ExecutorService virtualExecutor,
                                               int concurrency, long durationMillis) throws Exception {
        Phase phase = new Phase(durationMillis);
        List<WorkerStats> workers = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            workers.add(new WorkerStats(durationMillis));
        }
        HeapSamples heap = new HeapSamples();
        final long heapBefore = MEMORY.getHeapMemoryUsage().getUsed();
        final long gcCountBefore = gcCount();
        final long gcMillisBefore = gcMillis();
        long cpuBefore = OS.getProcessCpuTime();
        long elapsed;
        long cpu;
        long heapAfter;
        try (ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor()) {
            phase.start();
            final ScheduledFuture<?> sampling = sampler.scheduleAtFixedRate(heap::sample, 0, 100, TimeUnit.MILLISECONDS);
            switch (backend) {
                case JFIBERS -> runFibers(api, phase, workers);
                case VIRTUAL_THREADS -> {
                    List<Future<?>> tasks = new ArrayList<>(concurrency);
                    for (WorkerStats stats : workers) {
                        tasks.add(virtualExecutor.submit(() -> runVirtual(api, phase, stats)));
                    }
                    for (Future<?> task : tasks) {
                        task.get();
                    }
                }
                case COMPLETABLE_FUTURE -> {
                    List<CompletableFuture<Void>> tasks = new ArrayList<>(concurrency);
                    for (WorkerStats stats : workers) {
                        FutureFlow flow = new FutureFlow(api, phase, stats);
                        tasks.add(flow.done);
                        flow.run();
                    }
                    CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
                }
                default -> throw new IllegalStateException("Unhandled backend: " + backend);
            }
            elapsed = System.nanoTime() - phase.started;
            cpu = OS.getProcessCpuTime() - cpuBefore;
            heapAfter = MEMORY.getHeapMemoryUsage().getUsed();
            sampling.cancel(false);
        }
        Map<String, Object> result = WorkerStats.summarize(workers);
        long successful = ((Number) result.get("successful_operations")).longValue();
        if (successful == 0) {
            throw new IllegalStateException("No successful operations: " + HttpSupport.json(result));
        }
        result.put("backend", backend.name());
        result.put("concurrency", concurrency);
        result.put("requested_seconds", durationMillis / 1000.0);
        result.put("elapsed_seconds_including_drain", elapsed / 1e9);
        result.put("drain_ms", Math.max(0, elapsed / 1e6 - durationMillis));
        result.put("successful_operations_per_second", successful * 1e9 / elapsed);
        result.put("successful_http_requests_per_second", 2 * successful * 1e9 / elapsed);
        result.put("client_cpu_seconds", cpu / 1e9);
        result.put("client_cpu_cores", (double) cpu / elapsed);
        result.put("client_cpu_ms_per_success", cpu / 1e6 / successful);
        result.put("heap_before_bytes", heapBefore);
        result.put("heap_after_bytes", heapAfter);
        result.put("heap_peak_sampled_bytes", heap.peak);
        result.put("heap_mean_sampled_bytes", heap.count == 0 ? 0 : heap.sum / heap.count);
        result.put("heap_sample_count", heap.count);
        result.put("gc_count", gcCount() - gcCountBefore);
        result.put("gc_millis", gcMillis() - gcMillisBefore);
        return result;
    }

    private static void runFibers(HttpSupport.Api api, Phase phase, List<WorkerStats> workers) {
        try (FiberManager manager = new FiberManager()) {
            List<Fiber<Void>> tasks = new ArrayList<>(workers.size());
            for (WorkerStats stats : workers) {
                Fiber<Void> task = HttpFiberWorkload.run(api, phase, stats);
                tasks.add(task);
                manager.schedule(task);
            }
            manager.run();
            for (Fiber<Void> task : tasks) {
                if (!task.isDone() || task.getFailure() != null) {
                    throw new IllegalStateException("HTTP fiber failed", task.getFailure());
                }
            }
        }
    }

    private static void runVirtual(HttpSupport.Api api, Phase phase, WorkerStats stats) {
        while (phase.admit()) {
            long started = System.nanoTime();
            try {
                int token = HttpSupport.validate(api.client.send(api.first, HttpSupport.BODY));
                HttpSupport.validate(api.client.send(api.second(token), HttpSupport.BODY));
                stats.record(started, null, phase);
            } catch (Exception failure) {
                stats.record(started, failure, phase);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Unexpected client interruption", failure);
                }
            }
        }
    }

    /** One outstanding operation per flow. The trampoline also handles synchronous completion safely. */
    private static final class FutureFlow implements Runnable {
        private final HttpSupport.Api api;
        private final Phase phase;
        private final WorkerStats stats;
        private final AtomicInteger work = new AtomicInteger();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private volatile boolean completed = true;

        FutureFlow(HttpSupport.Api api, Phase phase, WorkerStats stats) {
            this.api = api;
            this.phase = phase;
            this.stats = stats;
        }

        @Override
        public void run() {
            if (work.getAndIncrement() != 0) {
                return;
            }
            int pending = 1;
            do {
                if (completed) {
                    completed = false;
                    if (!phase.admit()) {
                        done.complete(null);
                    } else {
                        startOperation();
                    }
                }
                pending = work.addAndGet(-pending);
            } while (pending != 0);
        }

        private void startOperation() {
            long started = System.nanoTime();
            CompletableFuture<Void> operation;
            try {
                operation = api.firstAsync().thenCompose(first -> {
                    try {
                        return api.secondAsync(HttpSupport.validate(first));
                    } catch (Exception failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                }).thenAccept(second -> {
                    try {
                        HttpSupport.validate(second);
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                });
            } catch (Exception failure) {
                operation = CompletableFuture.failedFuture(failure);
            }
            operation.whenComplete((ignored, failure) -> {
                stats.record(started, failure, phase);
                completed = true;
                run();
            });
        }
    }

    static final class Phase {
        private final long durationNanos;
        private long started;

        Phase(long durationMillis) {
            durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMillis);
        }

        void start() {
            started = System.nanoTime();
        }

        boolean admit() {
            return System.nanoTime() - started < durationNanos;
        }
    }

    /** Thread-confined per flow; histogram upper bounds have at most 1.563% quantization error. */
    static final class WorkerStats {
        private static final int SUB_BUCKETS = 64;
        private final long[] histogram = new long[32 * SUB_BUCKETS];
        private final long[] perSecond;
        private long successful;
        private long errors;
        private long timeouts;
        private long totalNanos;
        private long maxNanos;
        private String firstError;

        WorkerStats(long durationMillis) {
            perSecond = new long[(int) ((durationMillis + 999) / 1000) + 11];
        }

        void record(long started, Throwable failure, Phase phase) {
            long ended = System.nanoTime();
            long nanos = ended - started;
            totalNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
            long micros = Math.max(1, (nanos + 999) / 1000);
            int exponent = 63 - Long.numberOfLeadingZeros(micros);
            long base = 1L << exponent;
            int bucket = exponent * SUB_BUCKETS + (int) ((micros - base) * SUB_BUCKETS / base);
            histogram[Math.min(bucket, histogram.length - 1)]++;
            if (failure == null) {
                successful++;
                int second = (int) ((ended - phase.started) / 1_000_000_000);
                perSecond[Math.min(second, perSecond.length - 1)]++;
            } else {
                errors++;
                Throwable root = failure;
                while ((root instanceof CompletionException || root instanceof ExecutionException) && root.getCause() != null) {
                    root = root.getCause();
                }
                if (root instanceof HttpTimeoutException || root instanceof TimeoutException) {
                    timeouts++;
                }
                if (firstError == null) {
                    firstError = root.toString();
                }
            }
        }

        static Map<String, Object> summarize(List<WorkerStats> workers) {
            WorkerStats total = new WorkerStats(1000);
            long[] perSecond = new long[workers.getFirst().perSecond.length];
            for (WorkerStats worker : workers) {
                total.successful += worker.successful;
                total.errors += worker.errors;
                total.timeouts += worker.timeouts;
                total.totalNanos += worker.totalNanos;
                total.maxNanos = Math.max(total.maxNanos, worker.maxNanos);
                if (total.firstError == null) {
                    total.firstError = worker.firstError;
                }
                for (int i = 0; i < total.histogram.length; i++) {
                    total.histogram[i] += worker.histogram[i];
                }
                for (int i = 0; i < perSecond.length; i++) {
                    perSecond[i] += worker.perSecond[i];
                }
            }
            long count = total.successful + total.errors;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("successful_operations", total.successful);
            result.put("errors", total.errors);
            result.put("timeouts", total.timeouts);
            result.put("first_error", total.firstError);
            result.put("latency_mean_ms", count == 0 ? 0 : total.totalNanos / 1e6 / count);
            result.put("latency_p50_upper_ms", total.percentile(count, 0.5));
            result.put("latency_p95_upper_ms", total.percentile(count, 0.95));
            result.put("latency_p99_upper_ms", total.percentile(count, 0.99));
            result.put("latency_max_ms", total.maxNanos / 1e6);
            result.put("successful_completions_per_second", java.util.Arrays.stream(perSecond).boxed().toList());
            return result;
        }

        private double percentile(long count, double quantile) {
            if (count == 0) {
                return 0;
            }
            long target = (long) Math.ceil(count * quantile);
            long seen = 0;
            for (int i = 0; i < histogram.length; i++) {
                seen += histogram[i];
                if (seen >= target) {
                    long base = 1L << (i / SUB_BUCKETS);
                    long upper = base + ((i % SUB_BUCKETS + 1) * base + SUB_BUCKETS - 1) / SUB_BUCKETS - 1;
                    return upper / 1000.0;
                }
            }
            throw new IllegalStateException("Incomplete latency histogram");
        }
    }

    private static final class HeapSamples {
        private long peak;
        private long sum;
        private long count;

        void sample() {
            long used = MEMORY.getHeapMemoryUsage().getUsed();
            peak = Math.max(peak, used);
            sum += used;
            count++;
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
    }
}
