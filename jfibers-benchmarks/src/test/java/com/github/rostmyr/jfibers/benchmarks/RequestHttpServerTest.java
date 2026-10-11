package com.github.rostmyr.jfibers.benchmarks;

import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequestHttpServerTest {
    @Test(timeout = 15_000)
    public void cancellationWaitsForHandlePublicationAndReportsActualOutcome() throws Exception {
        try (RequestTaskEngine engine = new RequestTaskEngine(RequestTaskEngine.Backend.FIBER_POOL_1, 1, 1_000)) {
            RequestTaskEngine.Submission completed = engine.submit(1, false, 5_000, 0);
            assertEquals(200, completed.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            // A fast task may already have finished while its HTTP entry is still registering.
            assertFalse(cancelDuringRegistration(new RequestHttpServer.Entry(null, 1), completed));

            RequestTaskEngine.Submission pending = engine.submit(2, true, 5_000, 0);
            assertTrue(cancelDuringRegistration(new RequestHttpServer.Entry(null, 2), pending));
            assertEquals(409, pending.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            assertTrue(engine.awaitQuiescent(Duration.ofSeconds(5)));
            engine.assertQuiescent();
        }
    }

    private static boolean cancelDuringRegistration(RequestHttpServer.Entry entry,
                                                   RequestTaskEngine.Submission submission) throws Exception {
        FutureTask<Boolean> cancelled = new FutureTask<>(entry::cancel);
        Thread caller = Thread.ofPlatform().name("http-cancellation-registration-test").unstarted(cancelled);
        try {
            synchronized (entry) {
                caller.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (caller.getState() != Thread.State.BLOCKED && !cancelled.isDone()
                    && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertEquals("Cancellation must await registration, not acknowledge a missing handle",
                    Thread.State.BLOCKED, caller.getState());
                assertFalse(cancelled.isDone());
                entry.install(submission);
            }
            return cancelled.get(5, TimeUnit.SECONDS);
        } finally {
            caller.join(5_000);
            assertFalse("Cancellation caller did not finish", caller.isAlive());
        }
    }
}
