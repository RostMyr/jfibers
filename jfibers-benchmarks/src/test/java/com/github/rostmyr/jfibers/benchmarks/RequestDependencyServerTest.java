package com.github.rostmyr.jfibers.benchmarks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequestDependencyServerTest {
    @Test(timeout = 15_000)
    public void realHttpStepsValidateIdentityAndDrainTimersAndWriters() throws Exception {
        try (RequestDependencyServer server = new RequestDependencyServer(8);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            for (int step = 0; step < 3; step++) {
                HttpResponse<byte[]> response = client.send(request(server, "id=-17&step=" + step
                    + "&delayMs=" + (step == 0 ? 0 : 10)), HttpSupport.BODY);
                assertEquals(200, response.statusCode());
                assertEquals(RequestDependencyServer.RESPONSE_BYTES, response.body().length);
                ByteBuffer body = ByteBuffer.wrap(response.body());
                assertEquals(-17, body.getLong());
                assertEquals(step, body.getLong());
                assertEquals(42, body.getLong());
            }
            await(() -> drained(server.snapshot()));
            Map<String, Object> stats = server.snapshot();
            assertEquals(3, count(stats, "requests"));
            assertEquals(3, count(stats, "responses"));
            assertEquals(3, count(stats, "status_200"));
            assertEquals(3L * RequestDependencyServer.RESPONSE_BYTES, count(stats, "response_bytes"));
            assertEquals(0, count(stats, "errors"));
            assertEquals(0, count(stats, "response_errors"));
        }
    }

    @Test(timeout = 15_000)
    public void delayedExchangeReleasesHandlerAndBoundsAdmittedWork() throws Exception {
        try (RequestDependencyServer server = new RequestDependencyServer(1);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            CompletableFuture<HttpResponse<byte[]>> delayed = client.sendAsync(
                request(server, "id=20&step=0&delayMs=1000"), HttpSupport.BODY);
            await(() -> {
                Map<String, Object> stats = server.snapshot();
                return count(stats, "pending_timers") == 1 && count(stats, "handler_active") == 0;
            });
            assertFalse(delayed.isDone());
            HttpResponse<byte[]> rejected = client.send(request(server, "id=21&step=0&delayMs=0"), HttpSupport.BODY);
            assertEquals(503, rejected.statusCode());
            assertFalse("Dependency delay must not block the overload response", delayed.isDone());
            assertEquals(200, delayed.get(5, TimeUnit.SECONDS).statusCode());
            HttpResponse<byte[]> invalid = client.send(request(server, "id=22&step=3&delayMs=0"), HttpSupport.BODY);
            assertEquals(400, invalid.statusCode());
            HttpResponse<byte[]> recovered = client.send(request(server, "id=23&step=2&delayMs=0"), HttpSupport.BODY);
            assertEquals(200, recovered.statusCode());
            await(() -> drained(server.snapshot()));
            Map<String, Object> stats = server.snapshot();
            assertEquals(4, count(stats, "requests"));
            assertEquals(4, count(stats, "responses"));
            assertEquals(2, count(stats, "status_200"));
            assertEquals(1, count(stats, "status_503"));
            assertEquals(1, count(stats, "status_400"));
            assertEquals(1, count(stats, "errors"));
            assertEquals(0, count(stats, "response_errors"));
            assertEquals(1, count(stats, "peak_admitted"));
        }
    }

    @Test(timeout = 15_000)
    public void shutdownCancelsPendingDelayAndReleasesExchangeExactlyOnce() throws Exception {
        try (RequestDependencyServer server = new RequestDependencyServer(1);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            CompletableFuture<HttpResponse<byte[]>> delayed = client.sendAsync(
                request(server, "id=30&step=1&delayMs=1000"), HttpSupport.BODY);
            await(() -> count(server.snapshot(), "pending_timers") == 1);
            server.close();
            await(delayed::isDone);
            assertTrue(drained(server.snapshot()));
            server.close();
            assertEquals(0, count(server.snapshot(), "active_admitted"));
        }
    }

    private static HttpRequest request(RequestDependencyServer server, String parameters) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/step?" + parameters))
            .timeout(Duration.ofSeconds(5)).GET().build();
    }

    private static boolean drained(Map<String, Object> stats) {
        for (String field : new String[] {"active_exchanges", "active_admitted", "pending_timers",
            "timer_queue_size", "response_queue", "response_active", "handler_queue", "handler_active"}) {
            if (count(stats, field) != 0) {
                return false;
            }
        }
        return true;
    }

    private static long count(Map<String, Object> stats, String field) {
        return ((Number) stats.get(field)).longValue();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue("Dependency did not reach the expected state", condition.getAsBoolean());
    }
}
