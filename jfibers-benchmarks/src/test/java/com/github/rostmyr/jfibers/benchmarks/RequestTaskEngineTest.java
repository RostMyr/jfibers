package com.github.rostmyr.jfibers.benchmarks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
            RequestTaskEngine.Backend.FIBER_POOL_OFFLOAD, 2, 20_000_000, 1)) {
            RequestTaskEngine.Submission submission = engine.submit(0, false, 0, 100);
            await(() -> ((Number) engine.snapshot().get("active_compute")).longValue() == 1);
            assertTrue(submission.cancel());
            assertRejection(engine, 100, "heavy");
            RequestTaskEngine.Result result = submission.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(409, result.status());
            assertTrue(result.computeQueueNanos() > 0);
            assertEquals(0L, engine.snapshot().get("active_compute"));
            long steps = ((Number) engine.snapshot().get("cpu_steps_cancelled")).longValue();
            assertTrue(steps >= 1 && steps <= 3);
            assertEquals(steps * 20_000_000, engine.snapshot().get("cpu_iterations_cancelled"));
            assertEquals(0L, engine.snapshot().get("cpu_steps_success"));
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

    @Test(timeout = 30_000)
    public void heavyAdmissionIsAtomicUnderConcurrentSubmissionsAndLeavesRoomForLightWork() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 16, 100_000, 3);
                 var producers = Executors.newFixedThreadPool(8)) {
                CountDownLatch start = new CountDownLatch(1);
                ArrayList<Future<RequestTaskEngine.Submission>> attempts = new ArrayList<>();
                for (int id = 0; id < 16; id++) {
                    long requestId = id * 100L;
                    attempts.add(producers.submit(() -> {
                        start.await();
                        try {
                            return engine.submit(requestId, true, 0, 0);
                        } catch (RequestTaskEngine.AdmissionRejectedException rejected) {
                            assertEquals("heavy", rejected.reason());
                            return null;
                        }
                    }));
                }
                start.countDown();
                ArrayList<RequestTaskEngine.Submission> admitted = new ArrayList<>();
                for (Future<RequestTaskEngine.Submission> attempt : attempts) {
                    RequestTaskEngine.Submission submission = attempt.get(5, TimeUnit.SECONDS);
                    if (submission != null) {
                        admitted.add(submission);
                    }
                }
                assertEquals(3, admitted.size());
                assertEquals(3, engine.snapshot().get("active_heavy"));
                assertEquals(3, engine.snapshot().get("peak_heavy"));
                assertEquals(13L, engine.snapshot().get("rejected_heavy"));
                assertEquals(0L, engine.snapshot().get("rejected_global"));
                assertEquals(0L, engine.snapshot().get("cpu_steps"));
                assertEquals(200, engine.submit(1, false, 5_000, 0).completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).status());
                assertEquals(3L, engine.snapshot().get("cpu_steps_success"));
                assertEquals(3_000L, engine.snapshot().get("cpu_iterations_success"));
                for (RequestTaskEngine.Submission submission : admitted) {
                    assertTrue(submission.cancel());
                    assertEquals(409, submission.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                }
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
                assertEquals(0L, engine.snapshot().get("cpu_steps_cancelled"));
                assertEquals(0, engine.snapshot().get("active_heavy"));
            }
        }
    }

    @Test(timeout = 30_000)
    public void heavyReservationRollsBackWhenNativeGlobalAdmissionRejects() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 1, 100_000, 1)) {
                RequestTaskEngine.Submission light = engine.submit(1, true, 0, 0);
                assertRejection(engine, 0, "global");
                assertRejection(engine, 100, "global");
                assertEquals(0, engine.snapshot().get("active_heavy"));
                assertEquals(0, engine.snapshot().get("peak_heavy"));
                light.cancel();
                assertEquals(409, light.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                RequestTaskEngine.Submission heavy = engine.submit(0, true, 0, 0);
                // Heavy admission is checked first when both limits are full.
                assertRejection(engine, 100, "heavy");
                assertEquals(1, engine.snapshot().get("active_heavy"));
                heavy.cancel();
                assertEquals(409, heavy.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                assertEquals(2L, engine.snapshot().get("rejected_global"));
                assertEquals(1L, engine.snapshot().get("rejected_heavy"));
                assertEquals(3L, engine.snapshot().get("rejected"));
                assertEquals(0L, engine.snapshot().get("cpu_steps"));
                engine.assertQuiescent();
            }
        }
    }

    @Test(timeout = 30_000)
    public void disabledHeavyLimitStillTracksWholeHeavyRequestLifetimes() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 4, 100_000)) {
                ArrayList<RequestTaskEngine.Submission> admitted = new ArrayList<>();
                for (int id = 0; id < 4; id++) {
                    admitted.add(engine.submit(id * 100L, true, 0, 0));
                }
                assertEquals(0, engine.snapshot().get("heavy_capacity"));
                assertEquals(4, engine.snapshot().get("active_heavy"));
                assertEquals(4, engine.snapshot().get("peak_heavy"));
                assertRejection(engine, 400, "global");
                assertEquals(0L, engine.snapshot().get("rejected_heavy"));
                for (RequestTaskEngine.Submission submission : admitted) {
                    submission.cancel();
                    submission.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                assertEquals(0, engine.snapshot().get("active_heavy"));
                engine.assertQuiescent();
            }
        }
    }

    @Test(timeout = 30_000)
    public void heavySlotSpansAllCpuStepsAndCancellationOrDeadlineCleanup() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 4, 100_000, 1)) {
                RequestTaskEngine.Submission cancelled = engine.submit(0, false, 0, 150);
                await(() -> ((Number) engine.snapshot().get("cleanup_started")).longValue() == 1);
                assertRejection(engine, 100, "heavy");
                assertTrue(cancelled.cancel());
                assertRejection(engine, 200, "heavy");
                assertEquals(200, engine.submit(1, false, 5_000, 0).completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).status());
                assertEquals(409, cancelled.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                assertEquals(3L, engine.snapshot().get("cpu_steps_cancelled"));
                assertEquals(300_000L, engine.snapshot().get("cpu_iterations_cancelled"));
                RequestTaskEngine.Submission timed = engine.submit(0, false, 100, 200);
                await(() -> ((Number) engine.snapshot().get("cleanup_started")).longValue() == 3);
                assertRejection(engine, 100, "heavy");
                assertEquals(504, timed.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                assertEquals(3L, engine.snapshot().get("cpu_steps_timed_out"));
                assertEquals(300_000L, engine.snapshot().get("cpu_iterations_timed_out"));
                assertEquals(9L, engine.snapshot().get("cpu_steps"));
                assertEquals(603_000L, engine.snapshot().get("cpu_iterations"));
                assertEquals(0L, engine.snapshot().get("cpu_steps_errors"));
                assertEquals(1, engine.snapshot().get("peak_heavy"));
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
            }
        }
    }

    @Test(timeout = 30_000)
    public void successfulWorkIsAttributedOnlyAfterAllActualCpuStepsFinish() throws Exception {
        for (RequestTaskEngine.Backend backend : RequestTaskEngine.Backend.values()) {
            try (RequestTaskEngine engine = new RequestTaskEngine(backend, 2, 100_000, 1)) {
                RequestTaskEngine.Submission heavy = engine.submit(0, false, 5_000, 0);
                RequestTaskEngine.Submission light = engine.submit(1, false, 5_000, 0);
                assertEquals(200, heavy.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                assertEquals(200, light.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                assertEquals(6L, engine.snapshot().get("cpu_steps_success"));
                assertEquals(303_000L, engine.snapshot().get("cpu_iterations_success"));
                assertEquals(6L, engine.snapshot().get("cpu_steps"));
                assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
                engine.assertQuiescent();
                engine.close();
                assertRejected(engine);
                assertEquals(1L, engine.snapshot().get("rejected_closed"));
                assertEquals(0L, engine.snapshot().get("rejected_global"));
                assertEquals(0L, engine.snapshot().get("rejected_heavy"));
                assertEquals(1L, engine.snapshot().get("rejected"));
            }
        }
    }

    private static void assertRejection(RequestTaskEngine engine, long id, String reason) {
        try {
            engine.submit(id, false, 5_000, 0);
            fail("Expected admission rejection: " + reason);
        } catch (RequestTaskEngine.AdmissionRejectedException rejected) {
            assertEquals(reason, rejected.reason());
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
