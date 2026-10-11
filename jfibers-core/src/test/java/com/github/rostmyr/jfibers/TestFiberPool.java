package com.github.rostmyr.jfibers;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberPool {
    @Test(timeout = 5000)
    public void shouldRunOwnersInParallelAndKeepAffinityAfterAwait() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CompletableFuture<Integer> input = new CompletableFuture<>();
        Set<Thread> owners = ConcurrentHashMap.newKeySet();
        List<FiberTask<Integer>> tasks = new ArrayList<>();
        try (FiberPool pool = new FiberPool(4, 4)) {
            for (int i = 0; i < 4; i++) {
                tasks.add(pool.submit(() -> {
                    Thread owner = Thread.currentThread();
                    FiberManager manager = FiberManagers.current();
                    owners.add(owner);
                    entered.countDown();
                    await(entered); // All factories must be running concurrently.
                    return new Fiber<Integer>() {
                        @Override
                        public int update() {
                            assertThat(Thread.currentThread()).isSameAs(owner);
                            assertThat(FiberManagers.current()).isSameAs(manager);
                            return state == 0 ? awaitFor(input) : waitForFutureResult();
                        }
                    };
                }));
            }
            await(entered);
            input.complete(42);
            pool.shutdown();
            for (FiberTask<Integer> task : tasks) {
                assertThat(result(task)).isEqualTo(42);
            }
            assertThat(owners).hasSize(4);
            assertThat(pool.awaitTermination(Duration.ofSeconds(2))).isTrue();
        }
    }

    @Test(timeout = 10000)
    public void shouldDrainImmediateGracefulShutdownAndWakeOnConcurrentSubmissions() throws Exception {
        for (int round = 0; round < 30; round++) {
            try (FiberPool pool = new FiberPool(4, 256);
                 var producers = Executors.newFixedThreadPool(4)) {
                List<java.util.concurrent.Future<List<FiberTask<Integer>>>> submissions = new ArrayList<>();
                for (int producer = 0; producer < 4; producer++) {
                    submissions.add(producers.submit(() -> {
                        List<FiberTask<Integer>> tasks = new ArrayList<>();
                        for (int i = 0; i < 64; i++) {
                            tasks.add(pool.submit(() -> immediate(42)));
                        }
                        return tasks;
                    }));
                }
                List<FiberTask<Integer>> tasks = new ArrayList<>();
                for (var submission : submissions) {
                    tasks.addAll(submission.get(2, TimeUnit.SECONDS));
                }
                pool.shutdown();
                assertThat(pool.awaitTermination(Duration.ofSeconds(2))).isTrue();
                for (var task : tasks) {
                    assertThat(result(task)).isEqualTo(42);
                }
                assertThat(pool.getTaskCount()).isZero();
            }
        }
        for (int i = 0; i < 30; i++) {
            try (FiberPool pool = new FiberPool(1, 1)) {
                FiberTask<Integer> task = pool.submit(() -> immediate(42));
                pool.shutdown();
                assertThat(result(task)).isEqualTo(42);
                assertThat(pool.awaitTermination(Duration.ofSeconds(2))).isTrue();
            }
        }
    }

    @Test(timeout = 5000)
    public void shouldBoundWaitingWorkAndProtectCompletionFromObserverMutation() throws Exception {
        CompletableFuture<Integer> input = new CompletableFuture<>();
        AtomicInteger rejectedFactory = new AtomicInteger();
        try (FiberPool pool = new FiberPool(1, 1)) {
            FiberTask<Integer> task = pool.submit(() -> waiting(input));
            task.completion().toCompletableFuture().complete(99);
            task.completion().toCompletableFuture().cancel(true);
            assertThat(task.isDone()).isFalse();
            assertThatThrownBy(() -> pool.submit(() -> {
                rejectedFactory.incrementAndGet();
                return immediate(1);
            })).isInstanceOf(RejectedExecutionException.class);
            assertThat(rejectedFactory).hasValue(0);
            input.complete(42);
            assertThat(result(task)).isEqualTo(42);
            assertThat(result(pool.submit(() -> immediate(7)))).isEqualTo(7);
            pool.shutdown();
            assertThatThrownBy(() -> pool.submit(() -> immediate(1)))
                .isInstanceOf(RejectedExecutionException.class);
        }
    }

    @Test(timeout = 5000)
    public void shouldCancelQueuedFactoriesAndFactoriesAlreadyStarting() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger queuedFactories = new AtomicInteger();
        try (FiberPool pool = new FiberPool(1, 2)) {
            try {
                FiberTask<Integer> starting = pool.submit(() -> {
                    entered.countDown();
                    await(release);
                    return new Fiber<Integer>() {
                        @Override
                        public int update() {
                            bodies.incrementAndGet();
                            return resultLiteral(42);
                        }
                    };
                });
                await(entered);
                FiberTask<Integer> queued = pool.submit(() -> {
                    queuedFactories.incrementAndGet();
                    return immediate(7);
                });
                assertThat(queued.cancel()).isTrue();
                assertCancelled(queued);
                FiberTask<Integer> replacement = pool.submit(() -> immediate(9));
                assertThat(starting.cancel()).isTrue();
                assertThat(starting.isCancellationRequested()).isTrue();
                assertThat(starting.isDone()).isFalse();
                assertThat(starting.cancel()).isFalse();
                release.countDown();
                assertCancelled(starting);
                assertThat(result(replacement)).isEqualTo(9);
                assertThat(bodies).hasValue(0);
                assertThat(queuedFactories).hasValue(0);
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 10000)
    public void shouldResolveCompletionCancellationRacesWithoutLeakingCapacity() throws Exception {
        try (FiberPool pool = new FiberPool(1, 1)) {
            for (int i = 0; i < 300; i++) {
                CompletableFuture<Integer> input = new CompletableFuture<>();
                FiberTask<Integer> task = pool.submit(() -> waiting(input));
                Thread completer = Thread.startVirtualThread(() -> input.complete(42));
                boolean cancelled = task.cancel();
                completer.join();
                if (cancelled) {
                    assertCancelled(task);
                } else {
                    assertThat(result(task)).isEqualTo(42);
                }
                assertThat(pool.getTaskCount()).isZero();
            }
        }
    }

    @Test(timeout = 5000)
    public void shouldIsolateFactoryErrorsAndRejectSharedFibers() throws Exception {
        try (FiberPool pool = new FiberPool(1, 2)) {
            AssertionError failure = new AssertionError("factory failed");
            FiberTask<Integer> failed = pool.submit(() -> { throw failure; });
            assertThatThrownBy(() -> result(failed)).hasCause(failure);
            assertThatThrownBy(() -> result(pool.submit(() -> null))).hasCauseInstanceOf(NullPointerException.class);
            Fiber<Integer> shared = waiting(new CompletableFuture<>());
            CountDownLatch scheduled = new CountDownLatch(1);
            FiberTask<Integer> first = pool.submit(() -> shared);
            // A factory on the same owner runs after scheduling the earlier submission.
            FiberTask<Integer> duplicate = pool.submit(() -> {
                scheduled.countDown();
                return shared;
            });
            await(scheduled);
            assertThatThrownBy(() -> result(duplicate)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(first.isDone()).isFalse();
            first.cancel();
            assertCancelled(first);
            assertThat(result(pool.submit(() -> immediate(42)))).isEqualTo(42);
        }
    }

    @Test(timeout = 5000)
    public void shouldExposeTimeoutAndAllowReentrantCallbacks() throws Exception {
        try (FiberPool pool = new FiberPool(1, 1)) {
            FiberTask<Integer> timed = pool.submit(() -> waiting(new CompletableFuture<Integer>())
                .withTimeout(Duration.ofMillis(20)));
            assertThatThrownBy(() -> result(timed)).hasCauseInstanceOf(TimeoutException.class);
            CompletableFuture<Integer> input = new CompletableFuture<>();
            FiberTask<Integer> first = pool.submit(() -> waiting(input));
            CompletableFuture<Integer> chained = first.completion()
                .thenCompose(value -> pool.submit(() -> immediate(value + 1)).completion()).toCompletableFuture();
            input.complete(41);
            assertThat(chained.get(2, TimeUnit.SECONDS)).isEqualTo(42);
        }
    }

    @Test(timeout = 5000)
    public void shouldCancelWaitingTasksAndPermitCloseOnAnOwner() throws Exception {
        FiberPool pool = new FiberPool(2, 2);
        CompletableFuture<Integer> external = new CompletableFuture<>();
        try {
            FiberTask<Integer> waiting = pool.submit(() -> waiting(external));
            FiberTask<Integer> closing = pool.submit(() -> {
                assertThatThrownBy(() -> pool.awaitTermination(Duration.ZERO))
                    .isInstanceOf(IllegalStateException.class);
                pool.close();
                return immediate(42);
            });
            assertThat(pool.awaitTermination(Duration.ofSeconds(2))).isTrue();
            assertCancelled(waiting);
            assertCancelled(closing);
            assertThat(external.isCancelled()).isFalse();
            assertThat(pool.isTerminated()).isTrue();
        } finally {
            pool.close();
        }
    }

    private static <T> T result(FiberTask<T> task) throws Exception {
        return task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static void assertCancelled(FiberTask<?> task) {
        assertThatThrownBy(() -> result(task)).hasCauseInstanceOf(CancellationException.class);
    }

    private static Fiber<Integer> immediate(int value) {
        return new Fiber<>() {
            @Override
            public int update() {
                return resultLiteral(value);
            }
        };
    }

    private static Fiber<Integer> waiting(CompletableFuture<Integer> input) {
        return new Fiber<>() {
            @Override
            public int update() {
                return state == 0 ? awaitFor(input) : waitForFutureResult();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
