package com.github.rostmyr.jfibers;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberComputations {
    @Test(timeout = 5000)
    public void shouldRejectOverloadAndRemoveCancelledQueueEntries() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try (var executor = FiberFutures.newComputeExecutor(1, 1)) {
            try {
                CompletableFuture<Integer> first = FiberFutures.submit(() -> {
                    assertThat(Thread.currentThread()).isNotSameAs(caller);
                    entered.countDown();
                    release.await();
                    return 42;
                }, executor);
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Integer> queued = FiberFutures.submit(() -> 7, executor);
                assertThatThrownBy(() -> FiberFutures.submit(() -> {
                    rejectedRan.set(true);
                    return 9;
                }, executor)).isInstanceOf(RejectedExecutionException.class);
                assertThat(queued.cancel(false)).isTrue();
                CompletableFuture<Integer> replacement = FiberFutures.submit(() -> 11, executor);
                release.countDown();
                assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(42);
                assertThat(replacement.get(2, TimeUnit.SECONDS)).isEqualTo(11);
                assertThat(rejectedRan).isFalse();
                assertThat(queued.isCancelled()).isTrue();
                assertThatThrownBy(() -> FiberFutures.submit(() -> { throw new AssertionError("original"); }, executor)
                    .get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(AssertionError.class);
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 5000)
    public void shouldPropagateInterruptChoiceToRunningComputations() throws Exception {
        for (boolean interrupt : new boolean[] {true, false}) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Boolean> exited = new CompletableFuture<>();
            try (var executor = FiberFutures.newComputeExecutor(1, 1)) {
                try {
                    CompletableFuture<Integer> task = FiberFutures.submit(() -> {
                        entered.countDown();
                        try {
                            release.await();
                            exited.complete(false);
                        } catch (InterruptedException expected) {
                            exited.complete(true);
                        }
                        return 42;
                    }, executor);
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(task.cancel(interrupt)).isTrue();
                    if (!interrupt) {
                        assertThat(exited.isDone()).isFalse();
                        release.countDown();
                    }
                    assertThat(exited.get(2, TimeUnit.SECONDS)).isEqualTo(interrupt);
                    assertThat(task.isCancelled()).isTrue();
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test(timeout = 5000)
    public void shouldCompleteQueuedObserversWhenExecutorIsStopped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (var executor = FiberFutures.newComputeExecutor(1, 2)) {
            CompletableFuture<Integer> running = FiberFutures.submit(() -> {
                entered.countDown();
                new CountDownLatch(1).await();
                return 42;
            }, executor);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> queued = FiberFutures.submit(() -> 7, executor);
            var ordinary = executor.submit(() -> 9);
            executor.shutdownNow();
            assertThat(queued.isCancelled()).isTrue();
            assertThat(ordinary.isCancelled()).isTrue();
            assertThatThrownBy(() -> running.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InterruptedException.class);
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
