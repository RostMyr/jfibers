package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rostyslav Myroshnychenko
 * on 13.06.2018.
 */
@RunWith(Parameterized.class)
public class TestFiberTransformer {
    @Parameterized.Parameter
    public String methodName;

    @Parameterized.Parameter(1)
    public Object expected;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(new Object[][] {
            {"getSequence", 0L},
            {"callFiber", "AB"},
            {"callFuture", "A"},
            {"getNothing", null},
            {"callFiberInReturn", "AB"},
            {"callFutureInReturn", "A"},
            {"callFiberWithArgument", "Hello WorldA"},
            {"callMethodChain", "Hello WorldChained Call!"},
            {"fiberWithSeveralCalls", "AB"},
            {"fiberWithStaticMethodCall", "AB"},
            {"fiberWithAssignment", "A"},
            {"fiberWithImmediateReturn", 1},
            {"callWideArguments", 9.5},
            {"stringConcatenation", "sequence=0"}
        });
    }

    @Test(timeout = 5000)
    public void shouldExecuteInstrumentedMethod() throws Exception {
        Class<?> modelClass = InstrumentedTestModel.load(TestFiberModel.class);
        Object model = modelClass.getConstructor().newInstance();
        Fiber<?> fiber = (Fiber<?>) modelClass.getMethod(methodName).invoke(model);
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        manager.schedule(new Fiber<Void>() {
            @Override
            public int update() {
                if (fiber.isReady()) {
                    manager.stop();
                    return -1;
                }
                return 0;
            }
        });
        manager.run();

        assertThat(fiber.getException()).isNull();
        assertThat(fiber.getResult()).isEqualTo(expected);
        assertThat(manager.getSize()).isZero();
    }

}
