package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import com.github.rostmyr.jfibers.FiberPool;
import com.github.rostmyr.jfibers.FiberTask;
import com.github.rostmyr.jfibers.FiberFutures;
import com.github.rostmyr.jfibers.FiberStatus;
import org.junit.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberLifecycle {
    @Test(timeout = 10000)
    public void shouldRetainPoolAdmissionUntilOwnedCleanupFinishes() throws Exception {
        for (boolean shutdown : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture(); FiberPool pool = new FiberPool(1, 1)) {
                CompletableFuture<String> cleanup = new CompletableFuture<>();
                fixture.type.getField("cleanup").set(fixture.model, cleanup);
                Fiber<?> parent = fixture.fiber("parent");
                FiberTask<?> task = pool.submit(() -> parent);
                try {
                    await(() -> parent.getStatus() == FiberStatus.WAITING);
                    Fiber<?> child = (Fiber<?>) fixture.type.getField("lastChild").get(fixture.model);
                    await(() -> child.getStatus() == FiberStatus.WAITING);
                    if (shutdown) {
                        pool.shutdownNow();
                        assertThat(pool.awaitTermination(Duration.ofMillis(20))).isFalse();
                    } else {
                        assertThat(task.cancel()).isTrue();
                    }
                    await(child::isCancellationRequested);
                    assertThat(task.isDone()).isFalse();
                    assertThat(pool.getTaskCount()).isEqualTo(1);
                    assertThatThrownBy(() -> pool.submit(() -> parent))
                        .isInstanceOf(RejectedExecutionException.class);
                    cleanup.complete("child;");
                    assertThatThrownBy(() -> task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(CancellationException.class);
                    assertThat(pool.getTaskCount()).isZero();
                    assertThat(parent.isDone()).isTrue();
                    assertThat(child.isDone()).isTrue();
                    assertThat(fixture.events()).contains("parent;", "child;");
                    assertThat(fixture.input.isCancelled()).isFalse();
                    assertCleared(parent);
                    assertCleared(child);
                } finally {
                    cleanup.complete("child;");
                }
            }
        }
    }

    @Test(timeout = 5000)
    public void shouldCancelOffloadedWorkFromTransformedFinally() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (Fixture fixture = new Fixture();
             var executor = FiberFutures.newComputeExecutor(1, 1);
             FiberPool pool = new FiberPool(1, 1)) {
            fixture.type.getField("executor").set(fixture.model, executor);
            fixture.type.getField("computation").set(fixture.model, (Callable<Integer>) () -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
                return 42;
            });
            Fiber<?> fiber = fixture.fiber("offload");
            FiberTask<?> task = pool.submit(() -> fiber);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            task.cancel();
            assertThatThrownBy(() -> task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CancellationException.class);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.events()).isEqualTo("compute;");
            assertCleared(fiber);
        }
    }

    @Test(timeout = 5000)
    public void shouldCancelAnAwaitAndReleaseCapturedReferences() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.start("child");
            await(() -> fiber.getStatus() == FiberStatus.WAITING);
            assertThat(fiber.cancel()).isTrue();
            fixture.join();
            assertThat(fiber.getStatus()).isEqualTo(FiberStatus.CANCELLED);
            assertThat(fixture.events()).isEqualTo("child;");
            assertThat(fixture.input.isCancelled()).isFalse();
            assertThat(fiber.cancel()).isFalse();
            assertCleared(fiber);
            fixture.input.complete(42); // A late callback must not resurrect the completed fiber.
            assertThat(fixture.manager.getSize()).isZero();
        }
    }

    @Test(timeout = 5000)
    public void shouldCancelBeforeStartWithoutEnteringTheBody() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.fiber("child");
            assertThat(fiber.getStatus()).isEqualTo(FiberStatus.NEW);
            assertThat(fiber.cancel()).isTrue();
            assertThat(fiber.isDone()).isTrue();
            assertThat(fixture.events()).isEmpty();
            assertCleared(fiber);
        }
    }

    @Test(timeout = 5000)
    public void shouldWaitForOwnedChildCleanupBeforeCompletingTheParent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CompletableFuture<String> cleanup = new CompletableFuture<>();
            fixture.type.getField("cleanup").set(fixture.model, cleanup);
            Fiber<?> parent = fixture.start("parent");
            await(() -> fixture.manager.getSize() == 2 && fixture.runner.getState() == Thread.State.WAITING);
            Fiber<?> child = (Fiber<?>) fixture.type.getField("lastChild").get(fixture.model);
            parent.cancel();
            await(() -> parent.getStatus() == FiberStatus.WAITING && child.isCancellationRequested()
                && child.getStatus() == FiberStatus.WAITING);
            assertThat(parent.isDone()).isFalse();
            cleanup.complete("child;");
            fixture.join();
            assertThat(parent.getStatus()).isEqualTo(FiberStatus.CANCELLED);
            assertThat(child.getStatus()).isEqualTo(FiberStatus.CANCELLED);
            assertThat(fixture.events()).contains("parent;", "child;");
            assertCleared(parent);
            assertCleared(child);
        }
    }

    @Test(timeout = 5000)
    public void shouldRetainOwnedChildCleanupFailuresOnTheParent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.type.getField("cleanup").set(fixture.model,
                CompletableFuture.failedFuture(new IllegalStateException("child cleanup failed")));
            Fiber<?> parent = fixture.start("parent");
            await(() -> fixture.manager.getSize() == 2 && fixture.runner.getState() == Thread.State.WAITING);
            parent.cancel();
            fixture.join();
            assertThat(parent.getFailure().getSuppressed()).hasSize(1);
            assertThat(parent.getFailure().getSuppressed()[0].getSuppressed()).hasSize(1);
            assertThat(parent.getFailure().getSuppressed()[0].getSuppressed()[0])
                .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test(timeout = 5000)
    public void shouldLeaveSharedDependenciesRunningWhenAnObserverIsCancelled() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Fiber<?> child = fixture.fiber("child");
            fixture.type.getField("shared").set(fixture.model, child);
            fixture.manager.schedule(child);
            Fiber<?> observer = fixture.start("observer");
            await(() -> observer.getStatus() == FiberStatus.WAITING && child.getStatus() == FiberStatus.WAITING);
            observer.cancel();
            await(observer::isDone);
            assertThat(child.isCancellationRequested()).isFalse();
            fixture.input.complete(42);
            fixture.join();
            assertThat(child.getStatus()).isEqualTo(FiberStatus.COMPLETED);
            assertThat(child.getResult()).isEqualTo(42);
        }
    }

    @Test(timeout = 5000)
    public void shouldTimeoutAtAnAwaitAndRunFinally() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.fiber("child").withTimeout(Duration.ofMillis(200));
            fixture.manager.schedule(fiber);
            fixture.manager.run();
            assertThat(fiber.getStatus()).isEqualTo(FiberStatus.TIMED_OUT);
            assertThat(fiber.getException()).isInstanceOf(TimeoutException.class);
            assertThat(fixture.events()).isEqualTo("child;");
            assertThat(fixture.input.isCancelled()).isFalse();
        }
    }

    @Test(timeout = 5000)
    public void shouldKeepCancellationStickyAndRetainCleanupFailures() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.start("caughtCancellation");
            await(() -> fiber.getStatus() == FiberStatus.WAITING);
            fiber.cancel();
            fixture.join();
            assertThat(fiber.getStatus()).isEqualTo(FiberStatus.CANCELLED);
            assertThat(fiber.getResult()).isNull();
            assertThat(fixture.events()).isEqualTo("cleanup;");
        }
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.start("cleanupFailure");
            await(() -> fiber.getStatus() == FiberStatus.WAITING);
            fiber.cancel();
            fixture.join();
            assertThat(fiber.getException().getSuppressed()).hasSize(1);
            assertThat(fiber.getException().getSuppressed()[0]).hasMessage("cleanup failed");
        }
    }

    @Test(timeout = 5000)
    public void shouldCloseAnIdleManagerAndCleanUpAfterStop() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.start("child");
            await(() -> fiber.getStatus() == FiberStatus.WAITING);
            fixture.manager.stop();
            fixture.runner.join(2000);
            assertThat(fiber.isDone()).isFalse();
            fixture.manager.close();
            assertThat(fiber.getStatus()).isEqualTo(FiberStatus.CANCELLED);
            assertThat(fixture.events()).isEqualTo("child;");
            assertThat(fixture.manager.getSize()).isZero();
        }
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.start("child");
            await(() -> fiber.getStatus() == FiberStatus.WAITING);
            fixture.manager.close();
            assertThat(fiber.isDone()).isTrue();
            assertThat(fixture.events()).isEqualTo("child;");
        }
    }

    @Test(timeout = 5000)
    public void shouldPropagateTheOriginalErrorToAParentHandler() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Fiber<?> fiber = fixture.fiber("catchChildError");
            fixture.manager.schedule(fiber);
            fixture.manager.run();
            assertThat(fiber.getStatus()).isEqualTo(FiberStatus.COMPLETED);
            assertThat(fiber.getResult()).isEqualTo(42);
            assertThat(fiber.getFailure()).isNull();
        }
    }

    private static void assertCleared(Fiber<?> fiber) throws Exception {
        for (Field field : fiber.getClass().getDeclaredFields()) {
            if (!field.getType().isPrimitive()) {
                assertThat(field.get(fiber)).as(field.getName()).isNull();
            }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class Fixture implements AutoCloseable {
        private final Class<?> type = InstrumentedTestModel.load(LifecycleModel.class);
        private final Object model = type.getConstructor().newInstance();
        private final CompletableFuture<Integer> input = new CompletableFuture<>();
        private final FiberManager manager = new FiberManager();
        private Thread runner;

        Fixture() throws Exception {
            type.getField("input").set(model, input);
        }

        Fiber<?> fiber(String method) throws Exception {
            return (Fiber<?>) type.getMethod(method).invoke(model);
        }

        Fiber<?> start(String method) throws Exception {
            Fiber<?> fiber = fiber(method);
            manager.schedule(fiber);
            runner = new Thread(manager::run);
            runner.setDaemon(true);
            runner.start();
            return fiber;
        }

        void join() throws Exception {
            runner.join(2000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(manager.getSize()).isZero();
        }

        String events() throws Exception {
            return type.getField("events").get(model).toString();
        }

        @Override
        public void close() {
            input.complete(42);
            try {
                ((CompletableFuture<?>) type.getField("cleanup").get(model)).cancel(false);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            manager.close();
        }
    }
}
