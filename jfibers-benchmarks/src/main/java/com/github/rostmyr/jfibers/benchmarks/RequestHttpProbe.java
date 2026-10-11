package com.github.rostmyr.jfibers.benchmarks;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identical open-loop HTTP client for every server backend; a separate JVM from the server. */
public final class RequestHttpProbe {
    private static final OperatingSystemMXBean OS =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final HttpResponse.BodyHandler<byte[]> BODY = HttpResponse.BodyHandlers.ofByteArray();
    private final URI base;
    private final HttpClient client;
    private final int rate;
    private final int heavyIterations;
    private final int deadlineMillis;
    private final int sloMillis;
    private final int maxInFlight;
    private final int capacity;
    private final int heavyCapacity;
    private final long heavyChecksum;
    private final long lightChecksum;
    private final AtomicLong nextId = new AtomicLong(-1_000_000);

    private RequestHttpProbe(URI base, HttpClient client, int rate, int heavyIterations,
                             int deadlineMillis, int sloMillis, int maxInFlight, int capacity, int heavyCapacity) {
        this.base = base;
        this.client = client;
        this.rate = rate;
        this.heavyIterations = heavyIterations;
        this.deadlineMillis = deadlineMillis;
        this.sloMillis = sloMillis;
        this.maxInFlight = maxInFlight;
        this.capacity = capacity;
        if (heavyCapacity < 0 || heavyCapacity > capacity) {
            throw new IllegalArgumentException("Heavy capacity must be zero or at most the root capacity");
        }
        this.heavyCapacity = heavyCapacity;
        // The computation uses the same seed for each request: validate without repeating CPU work on the client.
        heavyChecksum = RequestTaskEngine.expected(0, heavyIterations);
        lightChecksum = RequestTaskEngine.expected(1, heavyIterations);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 9 && args.length != 10) {
            throw new IllegalArgumentException("baseURI rate warmupMs measureMs heavyIterations deadlineMs sloMs maxInFlight capacity [heavyCapacity]");
        }
        int rate = positive(args[1]);
        int warmupMillis = positive(args[2]);
        int measureMillis = positive(args[3]);
        try (ExecutorService executor = Executors.newFixedThreadPool(4);
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                 .connectTimeout(Duration.ofSeconds(5)).executor(executor).build();
             BufferedReader commands = new BufferedReader(new InputStreamReader(System.in))) {
            RequestHttpProbe probe = new RequestHttpProbe(URI.create(args[0]), client, rate,
                positive(args[4]), positive(args[5]), positive(args[6]), positive(args[7]), positive(args[8]),
                args.length == 10 ? Integer.parseInt(args[9]) : 0);
            emit(Map.of("ready", true));
            for (String command; (command = commands.readLine()) != null;) {
                switch (command) {
                    case "check" -> emit(Map.of("check", probe.check()));
                    case "warmup" -> emit(Map.of("result", probe.run(warmupMillis)));
                    case "measure" -> {
                        System.gc();
                        emit(Map.of("result", probe.run(measureMillis)));
                    }
                    case "stop" -> { return; }
                    default -> throw new IllegalArgumentException("Unknown command: " + command);
                }
            }
        }
    }

    private static int positive(String argument) {
        int value = Integer.parseInt(argument);
        if (value <= 0) throw new IllegalArgumentException("Arguments must be positive");
        return value;
    }

    private static void emit(Object value) {
        System.out.println(HttpSupport.json(value));
        System.out.flush();
    }

    private HttpRequest work(long id, String mode, int deadline, int cleanup) {
        return HttpRequest.newBuilder(base.resolve("/work?id=" + id + "&mode=" + mode
            + "&deadlineMs=" + deadline + "&cleanupMs=" + cleanup))
            .timeout(Duration.ofSeconds(10)).GET().build();
    }

    private int cancel(long id) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve("/cancel?id=" + id))
            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(), BODY).statusCode();
    }

    private String stats() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(base.resolve("/stats"))
            .timeout(Duration.ofSeconds(10)).GET().build(), BODY);
        if (response.statusCode() != 200) throw new IllegalStateException("Stats failed: " + response.statusCode());
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long field(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("Missing stats field " + key + ": " + json);
        return Long.parseLong(matcher.group(1));
    }

    private static boolean drained(String json) {
        return field(json, "active_roots") == 0 && field(json, "active_heavy") == 0
            && field(json, "pending_completions") == 0
            && field(json, "pending_sources") == 0
            && field(json, "pending_timers") == 0 && field(json, "active_compute") == 0
            && field(json, "compute_queue_size") == 0 && field(json, "timer_queue_size") == 0
            && field(json, "downstream_active") == 0 && field(json, "downstream_executor_queue") == 0
            && field(json, "downstream_executor_active") == 0
            && field(json, "active_exchanges") == 0
            && field(json, "response_queue") == 0 && field(json, "response_active") == 0;
    }

    private void awaitDrained() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String current;
        do {
            current = stats();
            if (drained(current)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Server failed to drain: " + current);
    }

    /** Cancellation, asynchronous cleanup, deadlines and hard admission limits, outside timed traffic. */
    private Map<String, Object> check() throws Exception {
        CheckCounts counts = new CheckCounts();
        awaitDrained();
        long cancelId = nextId(false);
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(work(cancelId, "hold", 5000, 20), BODY);
        long startLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (field(stats(), "pending_sources") == 0) {
            if (System.nanoTime() >= startLimit) throw new IllegalStateException("Cancellation work never started");
            Thread.sleep(2);
        }
        int cancellation;
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            cancellation = cancel(cancelId);
            if (cancellation == 202) break;
            if (cancellation != 404) throw new IllegalStateException("Unexpected cancel status " + cancellation);
            Thread.sleep(2);
        } while (System.nanoTime() < limit);
        if (cancellation != 202) throw new IllegalStateException("Cancellation was never admitted");
        Wire cancelled = validate(pending.get(10, TimeUnit.SECONDS), cancelId);
        counts.record(cancelled);
        checkTermination(cancelled, 409);
        awaitDrained();
        long timeoutId = nextId(false);
        Wire timedOut = validate(client.send(work(timeoutId, "hold", 100, 20), BODY), timeoutId);
        counts.record(timedOut);
        checkTermination(timedOut, 504);
        awaitDrained();

        Map<Long, CompletableFuture<HttpResponse<byte[]>>> blocked = hold(capacity, false);
        awaitHolding(capacity, false);
        long extraId = nextId(false);
        Wire rejected = validate(client.send(work(extraId, "normal", 5000, 0), BODY), extraId);
        counts.record(rejected);
        checkRejection(rejected, "global");
        cancelHeld(blocked, counts);
        awaitDrained();
        if (heavyCapacity > 0) {
            blocked = hold(heavyCapacity, true);
            awaitHolding(heavyCapacity, true);
            long heavyExtraId = nextId(true);
            Wire heavyRejected = validate(client.send(work(heavyExtraId, "hold", 5000, 0), BODY), heavyExtraId);
            counts.record(heavyRejected);
            checkRejection(heavyRejected, "heavy");
            long lightId = nextId(false);
            Wire light = validate(client.send(work(lightId, "normal", 5000, 0), BODY), lightId);
            counts.record(light);
            if (heavyCapacity == capacity) checkRejection(light, "global");
            else if (light.status != 200) throw new IllegalStateException("Heavy gate blocked light work: " + light);
            cancelHeld(blocked, counts);
            awaitDrained();
            long readmittedId = nextId(true);
            Wire readmitted = validate(client.send(work(readmittedId, "normal", 5000, 0), BODY), readmittedId);
            counts.record(readmitted);
            if (readmitted.status != 200) throw new IllegalStateException("Heavy admission slot leaked: " + readmitted);
            if (heavyCapacity == capacity) {
                lightId = nextId(false);
                light = validate(client.send(work(lightId, "normal", 5000, 0), BODY), lightId);
                counts.record(light);
                if (light.status != 200) throw new IllegalStateException("Global admission slot leaked: " + light);
            }
            awaitDrained();
        }
        // Keep the measured heavy/light arrival pattern identical across cap variants.
        nextId.set(1_000_000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", true);
        result.put("explicit_cancellation", 1);
        result.put("deadlines", 1);
        result.put("global_overload_cancelled", capacity);
        result.put("heavy_overload_cancelled", heavyCapacity);
        result.put("cancellation_cleanup_ms", cancelled.cleanupNanos / 1e6);
        result.put("deadline_cleanup_ms", timedOut.cleanupNanos / 1e6);
        result.put("http_status", counts.statuses);
        result.put("rejection_reasons", counts.rejections);
        result.put("offered", counts.statuses.values().stream().mapToLong(Long::longValue).sum());
        result.put("expected_cpu_steps_success", counts.cpuSteps);
        result.put("expected_cpu_iterations_success", counts.cpuIterations);
        return result;
    }

    private long nextId(boolean heavyClass) {
        long id;
        do { id = nextId.getAndIncrement(); } while (heavy(id) != heavyClass);
        return id;
    }

    private Map<Long, CompletableFuture<HttpResponse<byte[]>>> hold(int count, boolean heavyClass) {
        Map<Long, CompletableFuture<HttpResponse<byte[]>>> blocked = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            long id = nextId(heavyClass);
            blocked.put(id, client.sendAsync(work(id, "hold", 5000, 20), BODY));
        }
        return blocked;
    }

    private void awaitHolding(int count, boolean heavyClass) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            String current = stats();
            if (field(current, "active_roots") == count && field(current, "pending_sources") >= count
                && (!heavyClass || field(current, "active_heavy") == count)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < limit);
        throw new IllegalStateException("Could not fill the " + (heavyClass ? "heavy" : "global") + " capacity");
    }

    private void cancelHeld(Map<Long, CompletableFuture<HttpResponse<byte[]>>> blocked, CheckCounts counts) throws Exception {
        List<CompletableFuture<HttpResponse<byte[]>>> cancellations = new ArrayList<>();
        for (long id : blocked.keySet()) {
            cancellations.add(client.sendAsync(HttpRequest.newBuilder(base.resolve("/cancel?id=" + id))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(), BODY));
        }
        int index = 0;
        for (var entry : blocked.entrySet()) {
            if (cancellations.get(index++).get(10, TimeUnit.SECONDS).statusCode() != 202) {
                throw new IllegalStateException("Saturated request cancellation failed");
            }
            Wire result = validate(entry.getValue().get(10, TimeUnit.SECONDS), entry.getKey());
            counts.record(result);
            checkTermination(result, 409);
        }
    }

    private static void checkRejection(Wire result, String reason) {
        if (result.status != 503 || !reason.equals(result.rejectionReason)) {
            throw new IllegalStateException("Incorrect admission rejection: " + result);
        }
    }

    private final class CheckCounts {
        final Map<String, Long> statuses = new LinkedHashMap<>();
        final Map<String, Long> rejections = new LinkedHashMap<>(Map.of("heavy", 0L, "global", 0L));
        long cpuSteps;
        long cpuIterations;

        CheckCounts() {
            for (int code : new int[] {200, 409, 503, 504, 500}) statuses.put(Integer.toString(code), 0L);
        }

        void record(Wire result) {
            statuses.merge(Integer.toString(result.status), 1L, Long::sum);
            if (result.status == 503) rejections.merge(result.rejectionReason, 1L, Long::sum);
            if (result.status == 200) {
                cpuSteps += 3;
                cpuIterations += 3L * (heavy(result.id) ? heavyIterations : 1_000);
            }
        }
    }

    private static void checkTermination(Wire result, int expectedStatus) {
        if (result.status != expectedStatus || result.cleanupNanos < TimeUnit.MILLISECONDS.toNanos(19)) {
            throw new IllegalStateException("Incorrect termination/cleanup: " + result);
        }
    }

    private Map<String, Object> run(int durationMillis) throws Exception {
        long count = ((long) rate * durationMillis + 999) / 1000;
        if (count > 2_000_000) throw new IllegalArgumentException("At most two million arrivals per phase");
        Samples samples = new Samples((int) count);
        Semaphore permits = new Semaphore(maxInFlight);
        CountDownLatch completed = new CountDownLatch(samples.status.length);
        AtomicReference<Throwable> invalid = new AtomicReference<>();
        AtomicReference<String> transportFailure = new AtomicReference<>();
        long firstId = nextId.getAndAdd(count);
        long cpuBefore = OS.getProcessCpuTime();
        long allocationBefore = THREADS.getTotalThreadAllocatedBytes();
        long gcBefore = gcCount();
        long gcMillisBefore = gcMillis();
        long started = System.nanoTime();
        for (int i = 0; i < samples.status.length; i++) {
            final int index = i;
            long intended = started + i * 1_000_000_000L / rate;
            long delay;
            while ((delay = intended - System.nanoTime()) > 0) {
                LockSupport.parkNanos(delay);
                if (Thread.interrupted()) throw new InterruptedException();
            }
            samples.arrivalLag[i] = System.nanoTime() - intended;
            if (!permits.tryAcquire()) {
                samples.status[i] = -1;
                samples.latency[i] = System.nanoTime() - intended;
                samples.completedOffset[i] = System.nanoTime() - started;
                completed.countDown();
                continue;
            }
            long id = firstId + i;
            try {
                client.sendAsync(work(id, "normal", deadlineMillis, 0), BODY).whenComplete((response, failure) -> {
                    try {
                        if (failure == null) {
                            Wire wire = validate(response, id);
                            samples.status[index] = wire.status;
                            samples.queue[index] = wire.queueNanos;
                            samples.service[index] = wire.serviceNanos;
                            samples.cleanup[index] = wire.cleanupNanos;
                            samples.computeQueue[index] = wire.computeQueueNanos;
                            samples.rejectionReason[index] = wire.rejectionReason;
                        } else {
                            samples.status[index] = 0;
                            transportFailure.compareAndSet(null, failure.toString());
                        }
                    } catch (Throwable failureToValidate) {
                        invalid.compareAndSet(null, failureToValidate);
                        samples.status[index] = 0;
                    } finally {
                        long now = System.nanoTime();
                        samples.latency[index] = now - intended;
                        samples.completedOffset[index] = now - started;
                        permits.release();
                        completed.countDown();
                    }
                });
            } catch (RuntimeException dispatchFailure) {
                transportFailure.compareAndSet(null, dispatchFailure.toString());
                samples.status[i] = 0;
                samples.latency[i] = System.nanoTime() - intended;
                samples.completedOffset[i] = System.nanoTime() - started;
                permits.release();
                completed.countDown();
            }
        }
        // A low-rate phase can finish its last response before the intended arrival window ends.
        // Keep the full requested window in the throughput denominator even when no work remains.
        long remaining;
        while ((remaining = started + durationMillis * 1_000_000L - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) throw new InterruptedException();
        }
        if (!completed.await(90, TimeUnit.SECONDS)) throw new IllegalStateException("HTTP client did not drain");
        long elapsed = System.nanoTime() - started;
        long cpu = OS.getProcessCpuTime() - cpuBefore;
        long allocated = THREADS.getTotalThreadAllocatedBytes() - allocationBefore;
        long collections = gcCount() - gcBefore;
        long collectionMillis = gcMillis() - gcMillisBefore;
        if (invalid.get() != null) throw new IllegalStateException("Invalid HTTP payload", invalid.get());
        if (permits.availablePermits() != maxInFlight) throw new IllegalStateException("Leaked client permits");
        Map<String, Object> result = samples.summarize(firstId, sloMillis, durationMillis, elapsed);
        result.put("rate", rate);
        result.put("first_request_id", firstId);
        result.put("heavy_iterations", heavyIterations);
        result.put("heavy_capacity", heavyCapacity);
        result.put("deadline_ms", deadlineMillis);
        result.put("slo_ms", sloMillis);
        result.put("max_in_flight", maxInFlight);
        result.put("client_cpu_seconds", cpu / 1e9);
        result.put("client_cpu_cores", (double) cpu / elapsed);
        result.put("client_allocated_bytes", allocated);
        result.put("client_gc_count", collections);
        result.put("client_gc_millis", collectionMillis);
        result.put("first_transport_error", transportFailure.get());
        return result;
    }

    private Wire validate(HttpResponse<byte[]> response, long id) {
        if (response.body().length != 56) throw new IllegalStateException("Invalid body length " + response.body().length);
        ByteBuffer body = ByteBuffer.wrap(response.body());
        long status = body.getLong();
        long actualId = body.getLong();
        long checksum = body.getLong();
        List<String> reasons = response.headers().allValues("X-Admission-Rejection");
        String reason = reasons.isEmpty() ? null : reasons.getFirst();
        if (status == 503 ? reasons.size() != 1 || !("heavy".equals(reason) || "global".equals(reason))
            : !reasons.isEmpty()) {
            throw new IllegalStateException("Invalid admission rejection header: " + reasons + ", status=" + status);
        }
        Wire result = new Wire(Math.toIntExact(status), actualId, checksum, body.getLong(),
            body.getLong(), body.getLong(), body.getLong(), reason);
        if (response.statusCode() != status || actualId != id
            || (status != 200 && status != 409 && status != 503 && status != 504 && status != 500)
            || (status == 200 && checksum != (heavy(id) ? heavyChecksum : lightChecksum))) {
            throw new IllegalStateException("Invalid response: " + result + ", HTTP " + response.statusCode());
        }
        if (result.queueNanos < -1 || result.serviceNanos < -1 || result.cleanupNanos < 0
            || result.computeQueueNanos < 0 || (status == 200 && (result.queueNanos < 0 || result.serviceNanos < 0))) {
            throw new IllegalStateException("Invalid timing fields: " + result);
        }
        return result;
    }

    private static boolean heavy(long id) { return Math.floorMod(id * 37, 100) < 10; }

    private record Wire(int status, long id, long checksum, long queueNanos, long serviceNanos,
                        long cleanupNanos, long computeQueueNanos, String rejectionReason) { }

    private static final class Samples {
        final int[] status;
        final long[] latency;
        final long[] arrivalLag;
        final long[] completedOffset;
        final long[] queue;
        final long[] service;
        final long[] cleanup;
        final long[] computeQueue;
        final String[] rejectionReason;

        Samples(int count) {
            status = new int[count];
            latency = new long[count];
            arrivalLag = new long[count];
            completedOffset = new long[count];
            queue = new long[count];
            service = new long[count];
            cleanup = new long[count];
            computeQueue = new long[count];
            rejectionReason = new String[count];
            Arrays.fill(queue, -1);
            Arrays.fill(service, -1);
            Arrays.fill(cleanup, -1);
            Arrays.fill(computeQueue, -1);
        }

        Map<String, Object> summarize(long firstId, int sloMillis, int durationMillis, long elapsed) {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Long> statuses = new LinkedHashMap<>();
            Map<String, Long> rejections = new LinkedHashMap<>(Map.of("heavy", 0L, "global", 0L));
            for (int code : new int[] {200, 409, 503, 504, 500}) statuses.put(Integer.toString(code), 0L);
            long localRejected = 0;
            long transportErrors = 0;
            long onTime = 0;
            long[] perSecond = new long[(int) (elapsed / 1_000_000_000) + 1];
            for (int i = 0; i < status.length; i++) {
                int code = status[i];
                if (code == -1) localRejected++;
                else if (code == 0) transportErrors++;
                else statuses.merge(Integer.toString(code), 1L, Long::sum);
                if (code == 503) rejections.merge(rejectionReason[i], 1L, Long::sum);
                if (code == 200) {
                    if (latency[i] <= sloMillis * 1_000_000L) onTime++;
                    perSecond[(int) (completedOffset[i] / 1_000_000_000)]++;
                }
            }
            long successful = statuses.get("200");
            result.put("offered", status.length);
            result.put("dispatched", status.length - localRejected);
            result.put("local_rejected", localRejected);
            result.put("transport_errors", transportErrors);
            result.put("http_status", statuses);
            result.put("rejection_reasons", rejections);
            result.put("successful", successful);
            result.put("on_time_success", onTime);
            result.put("late_success", successful - onTime);
            result.put("requested_seconds", durationMillis / 1000.0);
            result.put("elapsed_seconds_including_drain", elapsed / 1e9);
            result.put("drain_ms", Math.max(0, elapsed / 1e6 - durationMillis));
            result.put("successful_per_second", successful * 1e9 / elapsed);
            result.put("goodput_per_second", onTime * 1e9 / elapsed);
            result.put("successful_completions_per_second", Arrays.stream(perSecond).boxed().toList());
            result.put("all_latency", distribution(latency, i -> true));
            result.put("successful_latency", distribution(latency, i -> status[i] == 200));
            result.put("light_latency", distribution(latency, i -> !heavy(firstId + i)));
            result.put("heavy_latency", distribution(latency, i -> heavy(firstId + i)));
            result.put("light_success_latency", distribution(latency, i -> status[i] == 200 && !heavy(firstId + i)));
            result.put("heavy_success_latency", distribution(latency, i -> status[i] == 200 && heavy(firstId + i)));
            for (boolean heavyClass : new boolean[] {false, true}) {
                Map<String, Long> outcomes = new LinkedHashMap<>();
                for (String key : List.of("offered", "200", "409", "503", "504", "500", "local_rejected",
                                         "transport_errors", "on_time_success", "late_success", "rejected_heavy",
                                         "rejected_global")) outcomes.put(key, 0L);
                for (int i = 0; i < status.length; i++) {
                    if (heavy(firstId + i) != heavyClass) continue;
                    outcomes.merge("offered", 1L, Long::sum);
                    outcomes.merge(status[i] == -1 ? "local_rejected" : status[i] == 0 ? "transport_errors"
                        : Integer.toString(status[i]), 1L, Long::sum);
                    if (status[i] == 503) outcomes.merge("rejected_" + rejectionReason[i], 1L, Long::sum);
                    if (status[i] == 200) outcomes.merge(latency[i] <= sloMillis * 1_000_000L
                        ? "on_time_success" : "late_success", 1L, Long::sum);
                }
                result.put(heavyClass ? "heavy_outcomes" : "light_outcomes", outcomes);
            }
            result.put("arrival_lag", distribution(arrivalLag, i -> true));
            for (String kind : List.of("all", "success", "light", "heavy")) {
                IntPredicate select = i -> status[i] > 0 && switch (kind) {
                    case "success" -> status[i] == 200;
                    case "light" -> !heavy(firstId + i);
                    case "heavy" -> heavy(firstId + i);
                    default -> true;
                };
                result.put(kind + "_server_queue", distribution(queue, select));
                result.put(kind + "_server_service", distribution(service, select));
                result.put(kind + "_server_cleanup", distribution(cleanup, select));
                result.put(kind + "_compute_queue", distribution(computeQueue, select));
            }
            return result;
        }

        private Map<String, Object> distribution(long[] values, IntPredicate select) {
            long[] selected = new long[values.length];
            int count = 0;
            double total = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] >= 0 && select.test(i)) {
                    selected[count++] = values[i];
                    total += values[i];
                }
            }
            if (count == 0) return Map.of("count", 0);
            Arrays.sort(selected, 0, count);
            return Map.of("count", count, "mean_ms", total / count / 1e6,
                "p50_ms", selected[(int) Math.ceil(count * .50) - 1] / 1e6,
                "p95_ms", selected[(int) Math.ceil(count * .95) - 1] / 1e6,
                "p99_ms", selected[(int) Math.ceil(count * .99) - 1] / 1e6,
                "max_ms", selected[count - 1] / 1e6);
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
