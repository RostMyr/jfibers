package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/** Controlled asynchronous workloads; the driver launches every measurement in a fresh JVM. */
public final class ScenarioProbe {
    static final OperatingSystemMXBean OS =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final int SUCCESS = 1, CANCELLED = 2, TIMED_OUT = 3, REJECTED = 4, ERROR = 5;

    enum Backend { JFIBERS, JFIBERS_4, VIRTUAL_THREADS, COMPLETABLE_FUTURE }
    enum Scenario { FANOUT, OPEN_LOOP, MIXED }

    private ScenarioProbe() {
    }

    public static void main(String[] args) throws Exception {
        Backend backend = Backend.valueOf(args[0]);
        Scenario scenario = Scenario.valueOf(args[1]);
        int warmupMillis = Integer.parseInt(args[2]);
        int measureMillis = Integer.parseInt(args[3]);
        int parameter = Integer.parseInt(args[4]); // arrival rate, or heavy-task percentage
        int workers = Integer.parseInt(args[5]);
        if (warmupMillis < 100 || warmupMillis > 60_000 || measureMillis < 100 || measureMillis > 60_000
                || workers < 1 || workers > 4096 || parameter < 0
                || (scenario == Scenario.OPEN_LOOP && (parameter < 1 || parameter > 200_000))
                || (scenario == Scenario.MIXED && parameter > 100)) {
            throw new IllegalArgumentException("Invalid workload arguments");
        }
        try (Resources resources = new Resources()) {
            Map<String, Object> warmup = phase(backend, scenario, warmupMillis, parameter, workers, resources);
            System.gc();
            Thread.sleep(100);
            Map<String, Object> result = phase(backend, scenario, measureMillis, parameter, workers, resources);
            System.out.println(HttpSupport.json(Map.of("warmup", warmup, "result", result)));
        }
    }

    static Map<String, Object> phase(Backend backend, Scenario scenario, int millis, int parameter,
                                   int workers, Resources resources) throws Exception {
        Config config = new Config(scenario, parameter, resources.service);
        int planned = scenario == Scenario.OPEN_LOOP ? Math.toIntExact((long) millis * parameter / 1000) : 0;
        // Fail instead of silently dropping samples if a closed-loop run outgrows the recording arrays.
        Stats stats = new Stats(planned == 0 ? Math.max(100_000, millis * 200) : planned);
        long gcCount = gcCount(), gcMillis = gcMillis();
        long allocated = THREADS.getTotalThreadAllocatedBytes();
        long cpu = OS.getProcessCpuTime();
        long started = System.nanoTime();
        Source source = scenario == Scenario.OPEN_LOOP ? new QueueSource(256)
            : new ClosedSource(config, stats, started + millis * 1_000_000L);
        Engine engine = new Engine(backend, source, workers, resources);
        long producerFinished = 0;
        if (source instanceof QueueSource queue) {
            for (int id = 0; id < planned; id++) {
                long intended = started + id * 1_000_000_000L / parameter;
                sleepUntil(intended);
                long offered = System.nanoTime();
                stats.arrivalLag[id] = Math.max(0, offered - intended);
                Job job = new Job(config, stats, id, intended);
                if (!queue.offer(job)) {
                    stats.outcome[id] = REJECTED;
                    stats.latency[id] = System.nanoTime() - intended;
                }
            }
            stats.issued.set(planned);
            producerFinished = System.nanoTime();
            queue.close();
        }
        engine.await();
        resources.service.awaitDrained();
        long elapsed = System.nanoTime() - started;
        cpu = OS.getProcessCpuTime() - cpu;
        allocated = THREADS.getTotalThreadAllocatedBytes() - allocated;
        long collections = gcCount() - gcCount;
        long collectionMillis = gcMillis() - gcMillis;
        Map<String, Object> result = stats.summary();
        result.put("backend", backend.name());
        result.put("scenario", scenario.name());
        result.put("parameter", parameter);
        result.put("workers", workers);
        result.put("elapsed_seconds_including_drain", elapsed / 1e9);
        result.put("requested_seconds", millis / 1000.0);
        result.put("drain_ms", Math.max(0, elapsed / 1e6 - millis));
        result.put("completed_per_second", ((Number) result.get("accepted")).longValue() * 1e9 / elapsed);
        result.put("successful_per_second", ((Number) result.get("successful")).longValue() * 1e9 / elapsed);
        result.put("cpu_cores", (double) cpu / elapsed);
        result.put("allocated_bytes", allocated);
        result.put("allocated_bytes_per_accepted", (double) allocated / ((Number) result.get("accepted")).longValue());
        result.put("gc_count", collections);
        result.put("gc_millis", collectionMillis);
        result.put("pending_sources", resources.service.active.sum());
        result.put("pending_timers", resources.service.timer.getQueue().size());
        result.put("runtime_source", FiberManager.class.getProtectionDomain().getCodeSource().getLocation().toString());
        if (source instanceof QueueSource queue) {
            result.put("queue_capacity", queue.capacity);
            result.put("queue_peak", queue.peak());
            result.put("deadline_ms_from_intended_arrival", 100);
            result.put("producer_elapsed_seconds", (producerFinished - started) / 1e9);
            result.put("offered_per_requested_second", planned * 1000.0 / millis);
            result.put("arrival_lag", distribution(stats.arrivalLag, planned));
        }
        return result;
    }

    static final class Config {
        final Scenario scenario;
        final int parameter;
        final Service service;
        final int steps;
        final long lightChecksum, heavyChecksum;

        Config(Scenario scenario, int parameter, Service service) {
            this.scenario = scenario;
            this.parameter = parameter;
            this.service = service;
            steps = scenario == Scenario.MIXED ? 3 : 1;
            lightChecksum = expected(false);
            heavyChecksum = expected(true);
        }

        int iterations(boolean heavy) {
            return scenario == Scenario.MIXED ? (heavy ? 100_000 : 1_000)
                : scenario == Scenario.OPEN_LOOP ? 25_000 : 0;
        }

        long expected(boolean heavy) {
            long result = 0;
            for (int step = 0; step < steps; step++) {
                result += cpuWork((scenario == Scenario.FANOUT ? 168 : 42) ^ step, iterations(heavy));
            }
            return result;
        }
    }

    static final class Job {
        final Config config;
        final Stats stats;
        final int id;
        final long intended;
        final boolean heavy;
        final List<Ticket> tickets = new ArrayList<>(4);
        ScheduledFuture<?> control;
        long started;

        Job(Config config, Stats stats, int id, long intended) {
            this.config = config;
            this.stats = stats;
            this.id = id;
            this.intended = intended;
            heavy = config.scenario == Scenario.MIXED && id * 37L % 100 < config.parameter;
            stats.heavy[id] = heavy;
        }

        void start() {
            started = System.nanoTime();
            stats.queueDelay[id] = started - intended;
        }

        CompletableFuture<Integer> openStep(int step) {
            if (config.scenario == Scenario.FANOUT) {
                int mode = id % 4; // 25% cancelled, 25% timed out, 50% successful
                CompletableFuture<?>[] branches = new CompletableFuture<?>[4];
                for (int branch = 0; branch < 4; branch++) {
                    Ticket ticket = config.service.request(mode < 2 && branch == 3 ? -1 : (branch + 1) * 2_000_000L);
                    tickets.add(ticket);
                    branches[branch] = ticket.value;
                }
                CompletableFuture<Integer> all = CompletableFuture.allOf(branches).thenApply(ignored -> {
                    int sum = 0;
                    for (Ticket ticket : tickets) {
                        sum += ticket.value.join();
                    }
                    return sum;
                });
                if (mode < 2) {
                    control = config.service.timer.schedule(() -> all.completeExceptionally(mode == 0
                        ? new CancellationException("Requested operation cancellation")
                        : new TimeoutException("Operation deadline")), mode == 0 ? 5 : 20, TimeUnit.MILLISECONDS);
                }
                return all;
            }
            if (config.scenario == Scenario.OPEN_LOOP && System.nanoTime() - intended >= 100_000_000L) {
                return CompletableFuture.failedFuture(new TimeoutException("Expired in admission queue"));
            }
            Ticket ticket = config.service.request(1_000_000L);
            tickets.add(ticket);
            if (config.scenario == Scenario.OPEN_LOOP) {
                long remaining = Math.max(0, 100_000_000L - (System.nanoTime() - intended));
                control = config.service.timer.schedule(() -> ticket.value.completeExceptionally(
                    new TimeoutException("Request deadline")), remaining, TimeUnit.NANOSECONDS);
            }
            return ticket.value;
        }

        long compute(int value, int step) {
            int expected = config.scenario == Scenario.FANOUT ? 168 : 42;
            if (value != expected) {
                throw new IllegalStateException("Bad asynchronous result: " + value);
            }
            return cpuWork(value ^ step, config.iterations(heavy));
        }

        void finish(long checksum, Throwable failure) {
            if (control != null) {
                control.cancel(false);
            }
            int cancelled = 0;
            for (Ticket ticket : tickets) {
                if (ticket.cancel()) {
                    cancelled++;
                }
            }
            long finished = System.nanoTime();
            failure = unwrap(failure);
            int outcome = failure == null ? SUCCESS : failure instanceof CancellationException ? CANCELLED
                : failure instanceof TimeoutException ? TIMED_OUT : ERROR;
            if (config.scenario == Scenario.OPEN_LOOP && outcome == SUCCESS && finished - intended > 100_000_000L) {
                outcome = TIMED_OUT; // Include scheduling and non-preemptible compute in the end-to-end deadline.
            }
            int expected = config.scenario == Scenario.FANOUT
                ? (id % 4 == 0 ? CANCELLED : id % 4 == 1 ? TIMED_OUT : SUCCESS) : SUCCESS;
            if ((config.scenario != Scenario.OPEN_LOOP && outcome != expected)
                    || (config.scenario == Scenario.OPEN_LOOP && outcome != SUCCESS && outcome != TIMED_OUT)
                    || (outcome == SUCCESS && checksum != (heavy ? config.heavyChecksum : config.lightChecksum))) {
                outcome = ERROR;
            }
            stats.cancelledSources[id] = cancelled;
            stats.latency[id] = finished - intended;
            stats.serviceTime[id] = finished - started;
            stats.outcome[id] = outcome;
        }
    }

    static long cpuWork(long value, int iterations) {
        for (int i = 0; i < iterations; i++) {
            value ^= value >>> 12;
            value ^= value << 25;
            value ^= value >>> 27;
            value *= 0x2545F4914F6CDD1DL;
        }
        return value;
    }

    static final class Service implements AutoCloseable {
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(4);
        final LongAdder active = new LongAdder();

        Service() {
            timer.setRemoveOnCancelPolicy(true);
            timer.prestartAllCoreThreads();
        }

        Ticket request(long delay) {
            Ticket ticket = new Ticket(this);
            if (delay >= 0) {
                ticket.timer = timer.schedule(() -> ticket.value.complete(42), delay, TimeUnit.NANOSECONDS);
            }
            return ticket;
        }

        void awaitDrained() {
            // Source callbacks can still be returning after they wake the final worker.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (active.sum() != 0 || !timer.getQueue().isEmpty()) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("Undrained asynchronous operations or timers");
                }
                LockSupport.parkNanos(100_000L);
            }
        }

        @Override
        public void close() {
            timer.shutdownNow();
            try {
                if (!timer.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Service did not terminate");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    static final class Ticket {
        final CompletableFuture<Integer> value = new CompletableFuture<>();
        ScheduledFuture<?> timer;

        Ticket(Service service) {
            service.active.increment();
            value.whenComplete((ignored, failure) -> service.active.decrement());
        }

        boolean cancel() {
            boolean cancelled = value.cancel(false);
            if (timer != null) {
                timer.cancel(false);
            }
            return cancelled;
        }
    }

    interface Source {
        CompletableFuture<Job> take();
    }

    static final class ClosedSource implements Source {
        final Config config;
        final Stats stats;
        final long until;

        ClosedSource(Config config, Stats stats, long until) {
            this.config = config;
            this.stats = stats;
            this.until = until;
        }

        @Override
        public CompletableFuture<Job> take() {
            long now = System.nanoTime();
            if (now >= until) {
                return CompletableFuture.completedFuture(null);
            }
            int id = stats.issued.getAndIncrement();
            if (id >= stats.outcome.length) {
                throw new IllegalStateException("Sample capacity exceeded");
            }
            return CompletableFuture.completedFuture(new Job(config, stats, id, now));
        }
    }

    /** Completion happens outside the monitor; queue capacity excludes jobs already assigned to a worker. */
    static final class QueueSource implements Source {
        final int capacity;
        final ArrayDeque<Job> queue = new ArrayDeque<>();
        final ArrayDeque<CompletableFuture<Job>> waiters = new ArrayDeque<>();
        boolean closed;
        int peak;

        QueueSource(int capacity) {
            this.capacity = capacity;
        }

        synchronized int peak() {
            return peak;
        }

        @Override
        public synchronized CompletableFuture<Job> take() {
            if (!queue.isEmpty()) {
                return CompletableFuture.completedFuture(queue.removeFirst());
            }
            if (closed) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Job> waiter = new CompletableFuture<>();
            waiters.addLast(waiter);
            return waiter;
        }

        boolean offer(Job job) {
            CompletableFuture<Job> waiter;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Queue is closed");
                }
                waiter = waiters.pollFirst();
                if (waiter == null) {
                    if (queue.size() == capacity) {
                        return false;
                    }
                    queue.addLast(job);
                    peak = Math.max(peak, queue.size());
                    return true;
                }
            }
            waiter.complete(job);
            return true;
        }

        void close() {
            List<CompletableFuture<Job>> pending;
            synchronized (this) {
                closed = true;
                pending = new ArrayList<>(waiters);
                waiters.clear();
            }
            pending.forEach(waiter -> waiter.complete(null));
        }
    }

    static final class Resources implements AutoCloseable {
        final Service service = new Service();
        final ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
        final ExecutorService continuations = Executors.newFixedThreadPool(4);

        @Override
        public void close() {
            service.close();
            virtual.close();
            continuations.close();
        }
    }

    static final class Engine {
        final List<CompletableFuture<Void>> completions = new ArrayList<>();

        Engine(Backend backend, Source source, int workers, Resources resources) {
            if (backend == Backend.JFIBERS || backend == Backend.JFIBERS_4) {
                int managers = backend == Backend.JFIBERS_4 ? 4 : 1;
                for (int group = 0; group < managers; group++) {
                    int size = workers / managers + (group < workers % managers ? 1 : 0);
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    completions.add(done);
                    Thread.ofPlatform().daemon(true).name("scenario-fibers-" + group).start(() -> {
                        try (FiberManager manager = new FiberManager()) {
                            List<Fiber<Void>> fibers = new ArrayList<>();
                            for (int worker = 0; worker < size; worker++) {
                                Fiber<Void> fiber = ScenarioFiberWorkload.worker(source);
                                fibers.add(fiber);
                                manager.schedule(fiber);
                            }
                            manager.run();
                            for (Fiber<Void> fiber : fibers) {
                                if (!fiber.isDone() || fiber.getFailure() != null) {
                                    throw new IllegalStateException("Worker fiber failed", fiber.getFailure());
                                }
                            }
                            done.complete(null);
                        } catch (Throwable failure) {
                            done.completeExceptionally(failure);
                        }
                    });
                }
            } else {
                for (int worker = 0; worker < workers; worker++) {
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    completions.add(done);
                    if (backend == Backend.VIRTUAL_THREADS) {
                        resources.virtual.submit(() -> {
                            try {
                                virtualWorker(source);
                                done.complete(null);
                            } catch (Throwable failure) {
                                done.completeExceptionally(failure);
                            }
                        });
                    } else {
                        new FutureWorker(source, resources.continuations, done).next();
                    }
                }
            }
        }

        void await() throws Exception {
            CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).get(90, TimeUnit.SECONDS);
        }
    }

    static void virtualWorker(Source source) throws Exception {
        Job job;
        while ((job = source.take().get()) != null) {
            job.start();
            long checksum = 0;
            Throwable failure = null;
            try {
                for (int step = 0; step < job.config.steps; step++) {
                    checksum += job.compute(job.openStep(step).get(), step);
                }
            } catch (Exception caught) {
                failure = caught;
            } finally {
                job.finish(checksum, failure);
            }
        }
    }

    /** Explicit executor keeps user compute off service and arrival-generator threads. */
    static final class FutureWorker {
        final Source source;
        final ExecutorService executor;
        final CompletableFuture<Void> done;

        FutureWorker(Source source, ExecutorService executor, CompletableFuture<Void> done) {
            this.source = source;
            this.executor = executor;
            this.done = done;
        }

        void next() {
            source.take().whenCompleteAsync((job, sourceFailure) -> {
                if (sourceFailure != null) {
                    done.completeExceptionally(sourceFailure);
                } else if (job == null) {
                    done.complete(null);
                } else {
                    job.start();
                    CompletableFuture<Long> pipeline = CompletableFuture.completedFuture(0L);
                    for (int step = 0; step < job.config.steps; step++) {
                        int index = step;
                        pipeline = pipeline.thenCompose(sum -> job.openStep(index)
                            .thenApplyAsync(value -> sum + job.compute(value, index), executor));
                    }
                    pipeline.handleAsync((checksum, failure) -> {
                        job.finish(checksum == null ? 0 : checksum, failure);
                        next();
                        return null;
                    }, executor).exceptionally(failure -> {
                        done.completeExceptionally(failure);
                        return null;
                    });
                }
            }, executor).exceptionally(failure -> {
                done.completeExceptionally(failure);
                return null;
            });
        }
    }

    static final class Stats {
        final AtomicInteger issued = new AtomicInteger();
        final int[] outcome, cancelledSources;
        final long[] latency, queueDelay, serviceTime, arrivalLag;
        final boolean[] heavy;

        Stats(int capacity) {
            outcome = new int[capacity];
            cancelledSources = new int[capacity];
            latency = new long[capacity];
            queueDelay = new long[capacity];
            serviceTime = new long[capacity];
            arrivalLag = new long[capacity];
            heavy = new boolean[capacity];
        }

        Map<String, Object> summary() {
            int count = issued.get();
            long[] counts = new long[6];
            long cancelled = 0;
            for (int i = 0; i < count; i++) {
                counts[outcome[i]]++;
                cancelled += cancelledSources[i];
            }
            if (count == 0 || counts[0] != 0 || counts[ERROR] != 0) {
                throw new IllegalStateException("Missing or incorrect results: " + Arrays.toString(counts));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("offered", count);
            result.put("accepted", count - counts[REJECTED]);
            result.put("successful", counts[SUCCESS]);
            result.put("cancelled", counts[CANCELLED]);
            result.put("timed_out", counts[TIMED_OUT]);
            result.put("rejected", counts[REJECTED]);
            result.put("errors", counts[ERROR]);
            result.put("sources_cancelled_in_cleanup", cancelled);
            result.put("accepted_latency", select(latency, 0, null));
            result.put("successful_latency", select(latency, SUCCESS, null));
            result.put("cancelled_latency", select(latency, CANCELLED, null));
            result.put("timeout_latency", select(latency, TIMED_OUT, null));
            result.put("queue_delay", select(queueDelay, 0, null));
            result.put("service_time", select(serviceTime, 0, null));
            result.put("light_latency", select(latency, SUCCESS, false));
            result.put("heavy_latency", select(latency, SUCCESS, true));
            return result;
        }

        Map<String, Object> select(long[] values, int status, Boolean weight) {
            long[] selected = new long[issued.get()];
            int size = 0;
            for (int i = 0; i < issued.get(); i++) {
                if ((status == 0 ? outcome[i] != REJECTED : outcome[i] == status)
                        && (weight == null || heavy[i] == weight)) {
                    selected[size++] = values[i];
                }
            }
            return distribution(selected, size);
        }
    }

    static Map<String, Object> distribution(long[] values, int count) {
        if (count == 0) {
            return Map.of("count", 0);
        }
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("p50_ms", sorted[(int) Math.ceil(count * 0.50) - 1] / 1e6);
        result.put("p95_ms", sorted[(int) Math.ceil(count * 0.95) - 1] / 1e6);
        result.put("p99_ms", sorted[(int) Math.ceil(count * 0.99) - 1] / 1e6);
        result.put("max_ms", sorted[count - 1] / 1e6);
        return result;
    }

    static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    static void sleepUntil(long deadline) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Arrival generator interrupted");
            }
        }
    }

    static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }
}
