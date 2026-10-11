package com.github.rostmyr.jfibers.benchmarks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Shared async HTTP transport; cancelling a body observer never cancels its physical request. */
final class RequestDependencyClient implements AutoCloseable {
    static final class Call {
        final CompletableFuture<Integer> value = new CompletableFuture<>();
        final CompletableFuture<Void> exited = new CompletableFuture<>();
    }

    private final URI base;
    private final ThreadPoolExecutor executor;
    private final HttpClient client;
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    RequestDependencyClient(URI base, int capacity) {
        this.base = Objects.requireNonNull(base, "base");
        if (base.getHost() == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))) {
            throw new IllegalArgumentException("Dependency base must be an absolute HTTP(S) URI");
        }
        int queueCapacity = Math.toIntExact(Math.max(1024L, Math.multiplyExact((long) capacity, 32)));
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().name("request-dependency-client-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
        client = HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    Call start(long id, int step, int delayMillis) {
        Call call = new Call();
        started.incrementAndGet();
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        try {
            URI uri = base.resolve("/step?id=" + id + "&step=" + step + "&delayMs=" + delayMillis);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((response, failure) -> {
                Throwable error = failure;
                if (error == null) {
                    try {
                        validate(response, id, step);
                    } catch (RuntimeException invalid) {
                        error = invalid;
                    }
                }
                finish(call, error);
            });
        } catch (RuntimeException failure) {
            finish(call, failure);
        }
        return call;
    }

    private static void validate(HttpResponse<byte[]> response, long id, int step) {
        if (response.statusCode() != 200 || response.body().length != 3 * Long.BYTES) {
            throw new IllegalStateException("Invalid dependency response status/length: " + response.statusCode()
                + "/" + response.body().length);
        }
        ByteBuffer body = ByteBuffer.wrap(response.body());
        if (body.getLong() != id || body.getLong() != step || body.getLong() != 42) {
            throw new IllegalStateException("Invalid dependency response identity or value");
        }
    }

    private void finish(Call call, Throwable failure) {
        if (failure == null) {
            succeeded.incrementAndGet();
        } else {
            failed.incrementAndGet();
        }
        completed.incrementAndGet();
        active.decrementAndGet();
        // Publish transport exit before waking a normal body. A cancelled body instead awaits
        // this gate in finally, retaining root/heavy admission until the HTTP call really ends.
        call.exited.complete(null);
        if (failure == null) {
            call.value.complete(42);
        } else {
            call.value.completeExceptionally(failure);
        }
    }

    Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("downstream_started", started.get());
        result.put("downstream_completed", completed.get());
        result.put("downstream_succeeded", succeeded.get());
        result.put("downstream_failed", failed.get());
        result.put("downstream_active", active.get());
        result.put("downstream_peak", peak.get());
        result.put("downstream_executor_queue", executor.getQueue().size());
        result.put("downstream_executor_active", executor.getActiveCount());
        return result;
    }

    boolean isQuiescent() {
        return active.get() == 0 && started.get() == completed.get()
            && executor.getQueue().isEmpty() && executor.getActiveCount() == 0;
    }

    @Override
    public void close() {
        client.close();
        executor.close();
        if (!isQuiescent()) {
            throw new IllegalStateException("Dependency transport did not drain: " + snapshot());
        }
    }
}
