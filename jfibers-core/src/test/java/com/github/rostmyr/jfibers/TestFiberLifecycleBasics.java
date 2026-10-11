package com.github.rostmyr.jfibers;

import org.junit.Test;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberLifecycleBasics {
    @Test(timeout = 5000)
    public void shouldNotWakeCleanupAfterCancellationWasDeliveredInline() {
        assertCleanupSurvivesQueuedCancellation(false);
    }

    @Test(timeout = 5000)
    public void shouldStillCancelOwnedChildrenAfterCancellationWasDeliveredInline() {
        assertCleanupSurvivesQueuedCancellation(true);
    }

    private static void assertCleanupSurvivesQueuedCancellation(boolean ownChild) {
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        AtomicInteger cleanupFinished = new AtomicInteger();
        AtomicInteger childUpdates = new AtomicInteger();
        Fiber<Void> child = new Fiber<>() {
            @Override
            public int update() {
                childUpdates.incrementAndGet();
                return -1;
            }
        };
        Fiber<Void> fiber = new Fiber<>() {
            @Override
            protected boolean supportsCancellationUnwind() {
                return true;
            }

            @Override
            public int update() {
                if (state == 0) {
                    if (ownChild) {
                        awaitFor(child);
                    }
                    // The owner can observe cancellation before the manager drains its signal.
                    cancel();
                    try {
                        checkCancellation();
                    } catch (CancellationException expected) {
                        return awaitFor(cleanup);
                    }
                    throw new AssertionError("Cancellation was not delivered");
                }
                try {
                    resumeAwait();
                    cleanupFinished.incrementAndGet();
                    return -1;
                } catch (Exception failure) {
                    return fail(failure);
                }
            }
        };
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        manager.schedule(new Fiber<Void>() {
            @Override
            public int update() {
                if (state == 0) {
                    // Give an incorrectly re-enqueued cleanup a turn while its future is pending.
                    return 1;
                }
                cleanup.complete(null);
                return -1;
            }
        });
        manager.run();
        assertThat(fiber.getStatus()).isEqualTo(FiberStatus.CANCELLED);
        assertThat(fiber.getFailure()).isInstanceOf(CancellationException.class);
        assertThat(fiber.getFailure().getSuppressed()).isEmpty();
        assertThat(cleanupFinished).hasValue(1);
        assertThat(manager.getSize()).isZero();
        if (ownChild) {
            assertThat(child.getStatus()).isEqualTo(FiberStatus.CANCELLED);
            assertThat(childUpdates).hasValue(0);
        }
    }

    @Test(timeout = 5000)
    public void shouldResolveCancellationCompletionRaces() throws Exception {
        for (int i = 0; i < 300; i++) {
            Fiber<Integer> fiber = new Fiber<>() {
                @Override
                public int update() {
                    return resultLiteral(42);
                }
            };
            FiberManager manager = new FiberManager();
            manager.schedule(fiber);
            CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
            Thread racer = Thread.startVirtualThread(() -> cancelled.complete(fiber.cancel()));
            manager.run();
            racer.join();
            assertThat(fiber.getStatus()).isEqualTo(cancelled.join() ? FiberStatus.CANCELLED : FiberStatus.COMPLETED);
            assertThat(fiber.getResult()).isEqualTo(cancelled.join() ? null : 42);
            assertThat(fiber.isDone()).isTrue();
            assertThat(manager.getSize()).isZero();
            assertThat(fiber.activeNext).isNull();
            assertThat(fiber.activePrevious).isNull();
        }
    }

    @Test(timeout = 5000)
    public void shouldApplyZeroTimeoutBeforeStarting() {
        AtomicInteger updates = new AtomicInteger();
        Fiber<Void> fiber = new Fiber<>() {
            @Override
            public int update() {
                updates.incrementAndGet();
                return -1;
            }
        };
        fiber.withTimeout(Duration.ZERO);
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        manager.run();
        assertThat(updates).hasValue(0);
        assertThat(fiber.getStatus()).isEqualTo(FiberStatus.TIMED_OUT);
        assertThatThrownBy(() -> fiber.withTimeout(Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test(timeout = 5000)
    public void shouldRejectDependencyCyclesWithoutHanging() {
        Fiber<?>[] pair = new Fiber<?>[2];
        pair[0] = new Fiber<Void>() {
            @Override
            public int update() {
                return state == 0 ? awaitFor(pair[1]) : waitForFiberResult();
            }
        };
        pair[1] = new Fiber<Void>() {
            @Override
            public int update() {
                return state == 0 ? awaitFor(pair[0]) : waitForFiberResult();
            }
        };
        FiberManager manager = new FiberManager();
        manager.schedule(pair[0]);
        manager.run();
        assertThat(pair[0].getStatus()).isEqualTo(FiberStatus.FAILED);
        assertThat(pair[1].getStatus()).isEqualTo(FiberStatus.FAILED);
        assertThat(manager.getSize()).isZero();
    }

    @Test(timeout = 5000)
    public void shouldDrainAndRejectNewRootTasksOnClose() {
        FiberManager manager = new FiberManager();
        Fiber<Void> fiber = new Fiber<>() {
            @Override
            public int update() {
                throw new AssertionError("must not start");
            }
        };
        manager.schedule(fiber);
        manager.close();
        assertThat(fiber.getStatus()).isEqualTo(FiberStatus.CANCELLED);
        assertThat(manager.getSize()).isZero();
        assertThatThrownBy(() -> manager.schedule(new Fiber<Void>() {
            @Override
            public int update() {
                return -1;
            }
        })).isInstanceOf(IllegalStateException.class);
    }
}
