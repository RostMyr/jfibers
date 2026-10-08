package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class TestFiberFailureExecution {
    @Parameterized.Parameter
    public String methodName;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> methods() {
        return Arrays.asList(new Object[][] {
            {"failedFuture"}, {"callFailedFuture"}, {"callFailedFiber"}, {"returnFailedFiber"}
        });
    }

    @Test(timeout = 5000)
    public void shouldAbortTheCallerAndPreserveTheFailure() throws Exception {
        Class<?> type = InstrumentedTestModel.load(TestFiberModel.class);
        Object model = type.getConstructor().newInstance();
        Fiber<?> failed = (Fiber<?>) type.getMethod(methodName).invoke(model);
        FiberManager manager = new FiberManager();
        manager.schedule(failed);
        manager.run();

        assertThat(failed.isReady()).isTrue();
        assertThat(failed.getResult()).isNull();
        assertThat(failed.getException()).isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(type.getMethod("sequenceValue").invoke(model)).isEqualTo(0L);
        assertThat(manager.getSize()).isZero();
    }
}
