package com.github.rostmyr.jfibers.benchmarks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RequestDependencyClientTest {
    @Test(timeout = 30_000)
    public void everyBackendRunsThreeValidatedHttpStepsPerSuccessfulRoot() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (DependencyServer server = new DependencyServer(Reply.GOOD, false);
                 RequestTaskEngine engine = engine(backend, server, 2, 1)) {
                RequestTaskEngine.Submission heavy = engine.submit(0, false, 5_000, 0);
                RequestTaskEngine.Submission light = engine.submit(1, false, 5_000, 0);
                RequestTaskEngine.Result heavyResult = result(heavy);
                RequestTaskEngine.Result lightResult = result(light);
                assertEquals(200, heavyResult.status());
                assertEquals(200, lightResult.status());
                assertEquals(RequestTaskEngine.expected(0, 100_000), heavyResult.checksum());
                assertEquals(RequestTaskEngine.expected(1, 100_000), lightResult.checksum());
                assertDrained(engine);
                assertEquals(6L, engine.snapshot().get("downstream_started"));
                assertEquals(6L, engine.snapshot().get("downstream_succeeded"));
                assertEquals(0L, engine.snapshot().get("downstream_failed"));
                assertEquals(6L, engine.snapshot().get("cpu_steps_success"));
                assertEquals(303_000L, engine.snapshot().get("cpu_iterations_success"));
                assertEquals("http", engine.snapshot().get("wait_mode"));
                assertEquals(1, engine.snapshot().get("compute_threads"));
                assertEquals(1, engine.snapshot().get("dependency_delay_ms"));
                assertEquals(Set.of("0:0", "0:1", "0:2", "1:0", "1:1", "1:2"), server.calls);
            }
        }
    }

    @Test(timeout = 30_000)
    public void cancellationRetainsGlobalAndHeavySlotsUntilTheHttpResponseFinishes() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (DependencyServer server = new DependencyServer(Reply.GOOD, true);
                 RequestTaskEngine engine = engine(backend, server, 1, 80)) {
                RequestTaskEngine.Submission submission = engine.submit(0, false, 0, 0);
                try {
                    assertTrue(server.entered.await(5, TimeUnit.SECONDS));
                    assertTrue(submission.cancel());
                    await(() -> number(engine, "cleanup_started") == 1);
                    assertFalse(submission.completion().toCompletableFuture().isDone());
                    assertEquals(1, number(engine, "active_heavy"));
                    assertEquals(1, number(engine, "downstream_active"));
                    assertEquals(1, number(engine, "pending_sources"));
                    assertRejected(engine, 100, "heavy");
                    assertRejected(engine, 1, "global");
                } finally {
                    server.release.countDown();
                }
                assertEquals(409, result(submission).status());
                assertDrained(engine);
                assertEquals(1L, engine.snapshot().get("downstream_succeeded"));
                assertEquals(0L, engine.snapshot().get("cpu_steps_cancelled"));
                assertEquals(0L, engine.snapshot().get("downstream_failed"));
            }
        }
    }

    @Test(timeout = 30_000)
    public void deadlineDoesNotReleaseAdmissionBeforePhysicalHttpCompletion() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (DependencyServer server = new DependencyServer(Reply.GOOD, true);
                 RequestTaskEngine engine = engine(backend, server, 1, 80)) {
                RequestTaskEngine.Submission submission = engine.submit(0, false, 300, 0);
                try {
                    assertTrue(server.entered.await(5, TimeUnit.SECONDS));
                    await(() -> number(engine, "cleanup_started") == 1);
                    assertFalse(submission.completion().toCompletableFuture().isDone());
                    assertEquals(1, number(engine, "downstream_active"));
                    assertRejected(engine, 100, "heavy");
                    assertRejected(engine, 1, "global");
                } finally {
                    server.release.countDown();
                }
                assertEquals(504, result(submission).status());
                assertDrained(engine);
                assertEquals(1L, engine.snapshot().get("downstream_completed"));
                assertEquals(0L, engine.snapshot().get("cpu_steps_timed_out"));
            }
        }
    }

    @Test(timeout = 30_000)
    public void protocolAndHttpFailuresAreCountedAndFailTheRoot() throws Exception {
        for (RequestTaskEngine.Backend backend : new RequestTaskEngine.Backend[] {
            RequestTaskEngine.Backend.FIBER_POOL_4, RequestTaskEngine.Backend.VIRTUAL_THREADS}) {
            for (Reply reply : new Reply[] {Reply.WRONG_ID, Reply.WRONG_STEP, Reply.WRONG_VALUE,
                Reply.SHORT_BODY, Reply.HTTP_FAILURE}) {
                try (DependencyServer server = new DependencyServer(reply, false);
                     RequestTaskEngine engine = engine(backend, server, 1, 1)) {
                    assertEquals(500, result(engine.submit(0, false, 5_000, 0)).status());
                    assertDrained(engine);
                    assertEquals(1L, engine.snapshot().get("downstream_started"));
                    assertEquals(1L, engine.snapshot().get("downstream_failed"));
                    assertEquals(0L, engine.snapshot().get("downstream_succeeded"));
                    assertEquals(1L, engine.snapshot().get("errors"));
                    assertEquals(0L, engine.snapshot().get("cpu_steps"));
                }
            }
        }
    }

    @Test(timeout = 30_000)
    public void dependencyFailureAfterCancellationStillInvalidatesTheTransportCounters() throws Exception {
        for (RequestTaskEngine.Backend backend : new RequestTaskEngine.Backend[] {
            RequestTaskEngine.Backend.FIBER_POOL_1, RequestTaskEngine.Backend.VIRTUAL_THREADS}) {
            try (DependencyServer server = new DependencyServer(Reply.WRONG_VALUE, true);
                 RequestTaskEngine engine = engine(backend, server, 1, 1)) {
                RequestTaskEngine.Submission submission = engine.submit(0, false, 0, 0);
                try {
                    assertTrue(server.entered.await(5, TimeUnit.SECONDS));
                    assertTrue(submission.cancel());
                } finally {
                    server.release.countDown();
                }
                assertEquals(409, result(submission).status());
                assertDrained(engine);
                assertEquals(1L, engine.snapshot().get("downstream_failed"));
                assertEquals(0L, engine.snapshot().get("errors"));
                assertEquals(1L, engine.snapshot().get("cancelled"));
            }
        }
    }

    @Test(timeout = 30_000)
    public void closeDrainsHttpWhileHoldModeKeepsUsingSyntheticSources() throws Exception {
        for (RequestTaskEngine.Backend backend : new RequestTaskEngine.Backend[] {
            RequestTaskEngine.Backend.FIBER_POOL_1, RequestTaskEngine.Backend.VIRTUAL_THREADS}) {
            try (DependencyServer server = new DependencyServer(Reply.GOOD, true);
                 RequestTaskEngine engine = engine(backend, server, 2, 1)) {
                RequestTaskEngine.Submission held = engine.submit(1, true, 0, 0);
                await(() -> number(engine, "pending_sources") == 1);
                assertEquals(0L, engine.snapshot().get("downstream_started"));
                RequestTaskEngine.Submission http = engine.submit(0, false, 0, 0);
                assertTrue(server.entered.await(5, TimeUnit.SECONDS));
                CompletableFuture<Void> closed = new CompletableFuture<>();
                Thread closer = Thread.startVirtualThread(() -> {
                    try {
                        engine.close();
                        closed.complete(null);
                    } catch (Throwable failure) {
                        closed.completeExceptionally(failure);
                    }
                });
                try {
                    await(() -> number(engine, "cleanup_started") == 2);
                    assertFalse(closed.isDone());
                    assertFalse(http.completion().toCompletableFuture().isDone());
                } finally {
                    server.release.countDown();
                }
                closed.get(5, TimeUnit.SECONDS);
                closer.join();
                assertEquals(409, result(held).status());
                assertEquals(409, result(http).status());
                assertDrained(engine);
            }
        }
    }

    private static RequestTaskEngine engine(RequestTaskEngine.Backend backend, DependencyServer server,
                                            int capacity, int delayMillis) {
        return new RequestTaskEngine(backend, capacity, 100_000, 1, 1, server.base(), delayMillis);
    }

    private static RequestTaskEngine.Result result(RequestTaskEngine.Submission submission) throws Exception {
        return submission.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static long number(RequestTaskEngine engine, String field) {
        return ((Number) engine.snapshot().get(field)).longValue();
    }

    private static void assertDrained(RequestTaskEngine engine) throws Exception {
        assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
        engine.assertQuiescent();
    }

    private static void assertRejected(RequestTaskEngine engine, long id, String reason) {
        try {
            engine.submit(id, false, 5_000, 0);
            fail("Expected admission rejection: " + reason);
        } catch (RequestTaskEngine.AdmissionRejectedException rejected) {
            assertEquals(reason, rejected.reason());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                fail("Timed out awaiting dependency lifecycle state");
            }
            Thread.sleep(1);
        }
    }

    private enum Reply { GOOD, WRONG_ID, WRONG_STEP, WRONG_VALUE, SHORT_BODY, HTTP_FAILURE }

    private static final class DependencyServer implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        final Set<String> calls = ConcurrentHashMap.newKeySet();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ExecutorService handlers = Executors.newFixedThreadPool(4);
        final HttpServer server;
        final Reply reply;

        DependencyServer(Reply reply, boolean blocked) throws IOException {
            this.reply = reply;
            release = new CountDownLatch(blocked ? 1 : 0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
            server.setExecutor(handlers);
            server.createContext("/step", this::handle);
            server.start();
        }

        URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange exchange) {
            try (exchange) {
                Map<String, String> query = new HashMap<>();
                for (String item : exchange.getRequestURI().getRawQuery().split("&")) {
                    String[] pair = item.split("=", 2);
                    query.put(pair[0], pair[1]);
                }
                long id = Long.parseLong(query.get("id"));
                int step = Integer.parseInt(query.get("step"));
                int delay = Integer.parseInt(query.get("delayMs"));
                if (!exchange.getRequestMethod().equals("GET") || step < 0 || step > 2 || delay < 0 || delay > 1000
                    || !calls.add(id + ":" + step)) {
                    throw new IllegalStateException("Unexpected dependency request");
                }
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Dependency response gate was not released");
                }
                Thread.sleep(delay);
                byte[] body = reply == Reply.SHORT_BODY ? new byte[8] : ByteBuffer.allocate(24)
                    .putLong(reply == Reply.WRONG_ID ? id + 1 : id)
                    .putLong(reply == Reply.WRONG_STEP ? step + 1 : step)
                    .putLong(reply == Reply.WRONG_VALUE ? 43 : 42).array();
                exchange.sendResponseHeaders(reply == Reply.HTTP_FAILURE ? 503 : 200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
            }
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            handlers.close();
            assertNull("Unexpected test dependency failure", failure.get());
        }
    }
}
