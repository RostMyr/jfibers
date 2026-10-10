package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class TestControlFlow {
    @Test(timeout = 5000)
    public void shouldPreserveControlFlowAndJvmValuesAcrossSuspension() throws Exception {
        Object[][] cases = {
            {"branch", 11}, {"loop", 35}, {"switches", "TEN"}, {"nestedExpressions", 12},
            {"wideStack", 9_000_000_011L}, {"wideLocals", 9_000_000_011.5}, {"scopedLocals", "10end"},
            {"arrays", 12}, {"mergedTypes", "ready"}, {"staticMethod", 42}, {"directChild", 11}, {"nullable", "null"}
        };
        for (Object[] scenario : cases) {
            Fixture fixture = new Fixture();
            Fiber<?> fiber = fixture.run((String) scenario[0]);
            assertThat(fiber.getException()).as((String) scenario[0]).isNull();
            assertThat(fiber.getResult()).as((String) scenario[0]).isEqualTo(scenario[1]);
        }
    }

    @Test(timeout = 5000)
    public void shouldTakeAlternativeBranches() throws Exception {
        Fixture fixture = new Fixture();
        fixture.type.getField("flag").setBoolean(fixture.model, false);
        assertThat(fixture.run("branch").getResult()).isEqualTo(-1);
        assertThat(fixture.run("mergedTypes").getResult()).isEqualTo("ready");
    }

    @Test(timeout = 5000)
    public void shouldCatchAwaitFailuresAtTheSourceInstruction() throws Exception {
        Fixture fixture = failedFixture();
        Fiber<?> fiber = fixture.run("caughtFailure");
        assertThat(fiber.getException()).isNull();
        assertThat(fiber.getResult()).isEqualTo("recovered");
        assertThat(fixture.events()).isEqualTo("caught;finally;");
    }

    @Test(timeout = 5000)
    public void shouldExecuteFinallyAfterSuccessAndFailure() throws Exception {
        Fixture success = new Fixture();
        assertThat(success.run("finallySuccess").getResult()).isEqualTo(10);
        assertThat(success.events()).isEqualTo("finally;");
        Fixture failure = failedFixture();
        assertThat(failure.run("finallyFailure").getException()).isInstanceOf(ExecutionException.class);
        assertThat(failure.events()).isEqualTo("finally;");
    }

    @Test(timeout = 5000)
    public void shouldKeepTheReturnValueWhileFinallySuspends() throws Exception {
        Fixture fixture = new Fixture();
        Fiber<?> fiber = fixture.run("awaitInFinally");
        assertThat(fiber.getException()).isNull();
        assertThat(fiber.getResult()).isEqualTo(7);
        assertThat(fixture.events()).isEqualTo("cleanup;");
    }

    @Test(timeout = 5000)
    public void shouldPreserveNestedHandlersAndSuppressedExceptions() throws Exception {
        Fixture nested = failedFixture();
        assertThat(nested.run("nestedFinally").getResult()).isEqualTo("recovered");
        assertThat(nested.events()).isEqualTo("inner;outer;");
        Fixture resource = failedFixture();
        Exception failure = resource.run("resource").getException();
        assertThat(failure).isInstanceOf(ExecutionException.class);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).hasMessage("close failure");
        assertThat(resource.events()).isEqualTo("close;");
    }

    @Test(timeout = 5000)
    public void shouldCatchChildFailures() throws Exception {
        Fixture fixture = failedFixture();
        assertThat(fixture.run("childCaught").getResult()).isEqualTo(99);
        assertThat(fixture.events()).isEqualTo("finally;");
    }

    @Test(timeout = 5000)
    public void shouldResumeDelayedFailuresThroughFinally() throws Exception {
        Fixture fixture = new Fixture();
        CompletableFuture<Integer> input = new CompletableFuture<>();
        fixture.type.getField("input").set(fixture.model, input);
        Fiber<?> fiber = fixture.fiber("caughtFailure");
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        Thread runner = new Thread(manager::run);
        runner.setDaemon(true);
        try {
            runner.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runner.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(runner.getState()).isEqualTo(Thread.State.WAITING);
            assertThat(fixture.events()).isEmpty();
            input.completeExceptionally(new IllegalArgumentException("delayed"));
            runner.join(2000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(fiber.getResult()).isEqualTo("recovered");
            assertThat(fixture.events()).isEqualTo("caught;finally;");
        } finally {
            manager.stop();
            runner.join(2000);
        }
    }

    private static Fixture failedFixture() throws Exception {
        Fixture fixture = new Fixture();
        fixture.type.getField("input").set(fixture.model,
            CompletableFuture.failedFuture(new IllegalArgumentException("source failure")));
        return fixture;
    }

    private static final class Fixture {
        private final Class<?> type;
        private final Object model;

        Fixture() throws Exception {
            type = InstrumentedTestModel.load(ControlFlowModel.class);
            model = type.getConstructor().newInstance();
        }

        Fiber<?> fiber(String method) throws Exception {
            return (Fiber<?>) type.getMethod(method).invoke(model);
        }

        Fiber<?> run(String method) throws Exception {
            Fiber<?> fiber = fiber(method);
            FiberManager manager = new FiberManager();
            manager.schedule(fiber);
            manager.run();
            assertThat(manager.getSize()).isZero();
            return fiber;
        }

        String events() throws Exception {
            return type.getField("events").get(model).toString();
        }
    }
}
