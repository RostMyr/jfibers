package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class TestFiberOverloads {
    @Parameterized.Parameter
    public Class<?>[] parameterTypes;

    @Parameterized.Parameter(1)
    public Object[] arguments;

    @Parameterized.Parameter(2)
    public String expected;

    @Parameterized.Parameters
    public static Collection<Object[]> overloads() {
        return Arrays.asList(new Object[][] {
            {new Class<?>[] {int.class}, new Object[] {42}, "42"},
            {new Class<?>[] {String.class}, new Object[] {"hello"}, "hello"},
            {new Class<?>[] {long.class, double.class}, new Object[] {4L, 2.5}, "6.5"},
            {new Class<?>[] {List.class}, new Object[] {List.of("generic argument")}, "generic argument"}
        });
    }

    @Test(timeout = 5000)
    public void shouldExecuteEveryOverload() throws Exception {
        Class<?> type = InstrumentedTestModel.load(TestFiberModel.class);
        Object model = type.getConstructor().newInstance();
        Fiber<?> fiber = (Fiber<?>) type.getMethod("overloaded", parameterTypes).invoke(model, arguments);
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        manager.run();

        assertThat(fiber.isReady()).isTrue();
        assertThat(fiber.getException()).isNull();
        assertThat(fiber.getResult()).isEqualTo(expected);
    }
}
