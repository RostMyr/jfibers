package com.github.rostmyr.jfibers;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class TestFiberErrors {
    @Parameterized.Parameter
    public boolean terminal;

    @Parameterized.Parameters(name = "terminal={0}")
    public static Collection<Object[]> modes() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    @Test
    public void shouldWaitForAnIncompleteFuture() {
        Fiber<Object> fiber = new SimpleFiber();
        CompletableFuture<Object> future = new CompletableFuture<>();
        fiber.setState(3);
        fiber.awaitFor(future);
        assertThat(awaitFuture(fiber)).isEqualTo(3);
        future.complete("ready");

        assertThat(awaitFuture(fiber)).isEqualTo(terminal ? -1 : 4);
        assertThat(fiber.getResult()).isEqualTo("ready");
        assertThat(fiber.getException()).isNull();
    }

    @Test
    public void shouldCaptureAFailedFuture() {
        Fiber<Object> fiber = new SimpleFiber();
        IllegalArgumentException failure = new IllegalArgumentException("failure");
        fiber.resultLiteral("stale result");
        fiber.awaitFor(CompletableFuture.failedFuture(failure));
        fiber.setState(awaitFuture(fiber));

        assertThat(fiber.isReady()).isTrue();
        assertThat(fiber.getResult()).isNull();
        assertThat(fiber.getException()).isInstanceOf(ExecutionException.class).hasCause(failure);
    }

    @Test
    public void shouldCaptureACancelledFuture() {
        Fiber<Object> fiber = new SimpleFiber();
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.cancel(true);
        fiber.awaitFor(future);
        fiber.setState(awaitFuture(fiber));

        assertThat(fiber.isReady()).isTrue();
        assertThat(fiber.getException()).isInstanceOf(CancellationException.class);
    }

    @Test
    public void shouldPropagateAFailedChildWithoutReschedulingIt() {
        Fiber<Object> child = new SimpleFiber();
        child.awaitFor(CompletableFuture.failedFuture(new IllegalStateException("child failure")));
        child.setState(awaitFuture(child));
        Fiber<Object> parent = new SimpleFiber();
        parent.resultLiteral("stale result");
        parent.awaitFor(child);
        parent.setState(terminal ? parent.waitForFiberResult() : parent.awaitFiber());

        assertThat(parent.isReady()).isTrue();
        assertThat(parent.getException()).isSameAs(child.getException());
        assertThat(parent.getResult()).isNull();
    }

    @Test
    public void shouldPreserveThreadInterruption() {
        Fiber<Object> fiber = new SimpleFiber();
        FutureTask<Object> future = new FutureTask<>(() -> "ready") {
            @Override
            public Object get() throws InterruptedException {
                throw new InterruptedException("interrupted");
            }
        };
        future.run();
        fiber.awaitFor(future);
        try {
            fiber.setState(awaitFuture(fiber));
            assertThat(fiber.isReady()).isTrue();
            assertThat(fiber.getException()).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private int awaitFuture(Fiber<?> fiber) {
        return terminal ? fiber.waitForFutureResult() : fiber.awaitFuture();
    }

    private static class SimpleFiber extends Fiber<Object> {
        @Override
        public int update() {
            return -1;
        }
    }
}
