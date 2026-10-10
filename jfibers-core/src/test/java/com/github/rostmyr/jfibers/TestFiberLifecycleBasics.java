package com.github.rostmyr.jfibers;

import org.junit.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberLifecycleBasics {
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
