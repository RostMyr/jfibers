package com.github.rostmyr.jfibers;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

public class TestFiberWaiting {
    @Test(timeout = 5000)
    public void shouldParkUntilCompletionAndResumeExactlyOnce() throws Exception {
        CompletableFuture<Integer> input = new CompletableFuture<>();
        AtomicInteger updates = new AtomicInteger();
        Fiber<Integer> fiber = waiting(input, updates);
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        Thread runner = start(manager);
        try {
            awaitPark(runner);
            assertThat(updates).hasValue(1);
            assertThat(manager.getSize()).isEqualTo(1);
            input.complete(42);
            runner.join(2000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(fiber.getResult()).isEqualTo(42);
            assertThat(updates).hasValue(2);
            assertThat(manager.getSize()).isZero();
        } finally {
            manager.stop();
            runner.join(2000);
        }
    }

    @Test(timeout = 5000)
    public void shouldNotLoseCompletionDuringCallbackRegistration() {
        for (boolean before : new boolean[] {true, false}) {
            CompletableFuture<Integer> input = new CompletableFuture<>() {
                @Override
                public CompletableFuture<Integer> whenComplete(BiConsumer<? super Integer, ? super Throwable> action) {
                    if (before) {
                        complete(42);
                    }
                    CompletableFuture<Integer> dependent = super.whenComplete(action);
                    complete(42);
                    return dependent;
                }
            };
            Fiber<Integer> fiber = waiting(input, new AtomicInteger());
            FiberManager manager = new FiberManager();
            manager.schedule(fiber);
            manager.run();
            assertThat(fiber.getResult()).isEqualTo(42);
            assertThat(manager.getSize()).isZero();
        }
    }

    @Test(timeout = 10000)
    public void shouldHandleConcurrentCompletionRaces() throws Exception {
        for (int i = 0; i < 300; i++) {
            CompletableFuture<Integer> input = new CompletableFuture<>();
            FiberManager manager = new FiberManager();
            Fiber<Integer> fiber = waiting(input, new AtomicInteger());
            manager.schedule(fiber);
            Thread completer = Thread.startVirtualThread(() -> input.complete(42));
            manager.run();
            completer.join();
            assertThat(fiber.getResult()).isEqualTo(42);
            assertThat(manager.getSize()).isZero();
        }
    }

    @Test(timeout = 5000)
    public void shouldWakeAnIdleManagerOnStop() throws Exception {
        FiberManager manager = new FiberManager();
        manager.schedule(waiting(new CompletableFuture<>(), new AtomicInteger()));
        Thread runner = start(manager);
        try {
            awaitPark(runner);
            manager.stop();
            runner.join(2000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(manager.getSize()).isEqualTo(1);
        } finally {
            manager.stop();
            runner.join(2000);
        }
    }

    @Test(timeout = 5000)
    public void shouldWakeAllParentsOfTheSameChild() throws Exception {
        CompletableFuture<Integer> input = new CompletableFuture<>();
        Fiber<Integer> child = waiting(input, new AtomicInteger());
        Fiber<Integer> first = parent(child);
        Fiber<Integer> second = parent(child);
        FiberManager manager = new FiberManager();
        manager.schedule(first);
        manager.schedule(second);
        Thread runner = start(manager);
        try {
            awaitPark(runner);
            input.complete(42);
            runner.join(2000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(first.getResult()).isEqualTo(42);
            assertThat(second.getResult()).isEqualTo(42);
            assertThat(manager.getSize()).isZero();
        } finally {
            manager.stop();
            runner.join(2000);
        }
    }

    @Test(timeout = 5000)
    public void shouldBoundPollingForPlainFutures() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        FutureTask<Integer> source = new FutureTask<>(() -> 42) {
            @Override
            public boolean isDone() {
                polls.incrementAndGet();
                return super.isDone();
            }
        };
        Fiber<Integer> fiber = waiting(source, new AtomicInteger());
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        Thread runner = start(manager);
        try {
            awaitPark(runner);
            assertThat(polls.get()).isLessThan(1000);
            source.run();
            runner.join(2000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(fiber.getResult()).isEqualTo(42);
        } finally {
            manager.stop();
            runner.join(2000);
        }
    }

    @Test(timeout = 5000)
    public void shouldAdaptAPlainFutureToNotifications() throws Exception {
        FutureTask<Integer> source = new FutureTask<>(() -> 42);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Integer> observed = FiberFutures.observe(source, executor);
            source.run();
            assertThat(observed.get(2, TimeUnit.SECONDS)).isEqualTo(42);
        }
    }

    private static Fiber<Integer> parent(Fiber<Integer> child) {
        return new Fiber<>() {
            @Override
            public int update() {
                return state == 0 ? awaitFor(child) : waitForFiberResult();
            }
        };
    }

    private static Fiber<Integer> waiting(Future<Integer> input, AtomicInteger updates) {
        return new Fiber<>() {
            @Override
            public int update() {
                updates.incrementAndGet();
                return state == 0 ? awaitFor(input) : waitForFutureResult();
            }
        };
    }

    private static Thread start(FiberManager manager) {
        Thread runner = new Thread(manager::run);
        runner.setDaemon(true);
        runner.start();
        return runner;
    }

    private static void awaitPark(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING
            && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
    }
}
