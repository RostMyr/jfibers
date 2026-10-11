package com.github.rostmyr.jfibers.benchmarks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RequestTaskEngineTest {
    @Test(timeout = 30_000)
    public void freshRootsCompleteWithIdenticalWorkAndDrain() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 32, 100_000)) {
                ArrayList<CompletableFuture<RequestTaskEngine.Result>> results = new ArrayList<>();
                for (int id = 0; id < 24; id++) {
                    results.add(engine.submit(id, false, 5_000, 0).completion().toCompletableFuture());
                }
                for (int id = 0; id < results.size(); id++) {
                    RequestTaskEngine.Result result = results.get(id).get(5, TimeUnit.SECONDS);
                    assertEquals(backend.toString(), 200, result.status());
                    assertEquals(RequestTaskEngine.expected(id, 100_000), result.checksum());
                    assertTrue(result.queueNanos() >= 0);
                    assertTrue(result.serviceNanos() > 0);
                }
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
                assertEquals(24L, engine.snapshot().get("success"));
            }
        }
    }

    @Test(timeout = 30_000)
    public void explicitCancellationRetainsCapacityThroughCleanup() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 1, 100_000)) {
                RequestTaskEngine.Submission submission = engine.submit(1, true, 0, 100);
                await(() -> ((Number) engine.snapshot().get("pending_sources")).longValue() == 1);
                assertTrue(submission.cancel());
                assertFalse(submission.cancel());
                CompletableFuture<RequestTaskEngine.Result> completion = submission.completion().toCompletableFuture();
                assertFalse("Cancellation must await cleanup", completion.isDone());
                assertRejected(engine);
                RequestTaskEngine.Result result = completion.get(5, TimeUnit.SECONDS);
                assertEquals(409, result.status());
                assertTrue(result.cleanupNanos() >= TimeUnit.MILLISECONDS.toNanos(100));
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
                assertEquals(200, engine.submit(1, false, 5_000, 0).completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).status());
            }
        }
    }

    @Test(timeout = 30_000)
    public void deadlineStaysDistinctFromExplicitCancellation() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 1, 100_000)) {
                RequestTaskEngine.Submission timed = engine.submit(1, true, 200, 50);
                await(() -> ((Number) engine.snapshot().get("pending_sources")).longValue() == 1);
                RequestTaskEngine.Result result = timed.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(504, result.status());
                assertFalse(timed.cancel());
                assertTrue(result.cleanupNanos() >= TimeUnit.MILLISECONDS.toNanos(50));
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
                assertEquals(1L, engine.snapshot().get("timed_out"));
                assertEquals(0L, engine.snapshot().get("cancelled"));
            }
        }
    }

    @Test(timeout = 30_000)
    public void cancellationRacingWithCleanupDoesNotAbandonTheAwait() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 1, 100_000)) {
                RequestTaskEngine.Submission submission = engine.submit(1, false, 0, 150);
                await(() -> ((Number) engine.snapshot().get("cleanup_started")).longValue() == 1);
                assertTrue(submission.cancel());
                assertRejected(engine);
                RequestTaskEngine.Result result = submission.completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
                assertEquals(409, result.status());
                assertEquals(RequestTaskEngine.expected(1, 100_000), result.checksum());
                assertTrue(result.cleanupNanos() >= TimeUnit.MILLISECONDS.toNanos(150));
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
            }
        }
    }

    @Test(timeout = 30_000)
    public void cancellationWaitsForActualOffloadedComputeExit() throws Exception {
        try (RequestTaskEngine engine = new RequestTaskEngine(
            RequestTaskEngine.Backend.FIBER_POOL_OFFLOAD, 1, 20_000_000)) {
            RequestTaskEngine.Submission submission = engine.submit(0, false, 0, 0);
            await(() -> ((Number) engine.snapshot().get("active_compute")).longValue() == 1);
            assertTrue(submission.cancel());
            RequestTaskEngine.Result result = submission.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(409, result.status());
            assertTrue(result.computeQueueNanos() > 0);
            assertEquals(0L, engine.snapshot().get("active_compute"));
            assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
            engine.assertQuiescent();
        }
    }

    @Test(timeout = 30_000)
    public void deadlineIncludesCleanupWithoutAbandoningIt() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 1, 100_000)) {
                RequestTaskEngine.Submission submission = engine.submit(1, false, 100, 200);
                await(() -> ((Number) engine.snapshot().get("cleanup_started")).longValue() == 1);
                RequestTaskEngine.Result result = submission.completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
                assertEquals(504, result.status());
                assertTrue(result.cleanupNanos() >= TimeUnit.MILLISECONDS.toNanos(200));
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
            }
        }
    }

    @Test(timeout = 30_000)
    public void closeCancelsAcceptedRequestsAndDrainsCleanup() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            RequestTaskEngine engine = new RequestTaskEngine(backend, 2, 100_000);
            try (engine) {
                RequestTaskEngine.Submission first = engine.submit(1, true, 0, 50);
                RequestTaskEngine.Submission second = engine.submit(2, true, 0, 50);
                await(() -> ((Number) engine.snapshot().get("pending_sources")).longValue() == 2);
                engine.close();
                assertEquals(409, first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                assertEquals(409, second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                assertEquals(2L, engine.snapshot().get("completed"));
                assertRejected(engine);
                engine.assertQuiescent();
            }
        }
    }

    @Test(timeout = 30_000)
    public void quiescenceWaitsForCompletionPublicationOutsideTheEngineLock() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 1, 100_000)) {
                RequestTaskEngine.Submission submission = engine.submit(1, true, 0, 0);
                await(() -> ((Number) engine.snapshot().get("pending_sources")).longValue() == 1);
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                CompletableFuture<Void> published = holdPublication(submission, entered, release);
                assertTrue(submission.cancel());
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    // The callback is running outside the engine lock; snapshots remain available.
                    assertEquals(1, engine.snapshot().get("pending_completions"));
                    assertFalse(engine.awaitQuiescent(Duration.ofMillis(10)));
                } finally {
                    release.countDown();
                }
                published.get(5, TimeUnit.SECONDS);
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
            }
        }
    }

    @Test(timeout = 10_000)
    public void queuedDeadlineExpiresBeforeTheOwnerCanStartTheBody() throws Exception {
        try (RequestTaskEngine engine = new RequestTaskEngine(
            RequestTaskEngine.Backend.FIBER_POOL_1, 1, 100_000)) {
            RequestTaskEngine.Submission first = engine.submit(1, true, 0, 0);
            await(() -> ((Number) engine.snapshot().get("pending_sources")).longValue() == 1);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> published = holdPublication(first, entered, release);
            assertTrue(first.cancel());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                // Its native slot is free, while the sole owner is deliberately held in the callback.
                RequestTaskEngine.Result result = engine.submit(2, false, 30, 100).completion()
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(504, result.status());
                assertEquals(-1, result.queueNanos());
                assertEquals(-1, result.serviceNanos());
                assertEquals(0, result.cleanupNanos());
                RequestTaskEngine.Submission replacement = engine.submit(3, true, 0, 100);
                assertTrue(replacement.cancel());
                RequestTaskEngine.Result cancelled = replacement.completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
                assertEquals(409, cancelled.status());
                assertEquals(-1, cancelled.queueNanos());
                assertEquals(0, cancelled.cleanupNanos());
            } finally {
                release.countDown();
            }
            published.get(5, TimeUnit.SECONDS);
            assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
            engine.assertQuiescent();
        }
    }

    private static CompletableFuture<Void> holdPublication(RequestTaskEngine.Submission submission,
                                                          CountDownLatch entered, CountDownLatch release) {
        return submission.completion().thenAccept(result -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Completion callback was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }).toCompletableFuture();
    }

    private static void assertRejected(RequestTaskEngine engine) {
        try {
            engine.submit(2, false, 5_000, 0);
            fail("Cleanup must retain the only admission slot");
        } catch (RejectedExecutionException expected) {
            // Deliberate admission test.
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                fail("Timed out awaiting request lifecycle state");
            }
            Thread.sleep(1);
        }
    }
}
