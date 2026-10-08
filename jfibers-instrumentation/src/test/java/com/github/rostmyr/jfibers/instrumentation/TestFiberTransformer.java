package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.objectweb.asm.ClassReader;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
        FiberTransformerResult result = FiberTransformer.instrument(TestFiberModel.class, false);
        assertThat(result.getMainClass()).isNotNull();

        Map<String, byte[]> definitions = new HashMap<>();
        for (byte[] bytes : result.getFibers().values()) {
            definitions.put(new ClassReader(bytes).getClassName().replace('/', '.'), bytes);
        }
        InstrumentedClassLoader loader = new InstrumentedClassLoader(definitions);
        Class<?> modelClass = loader.define(result.getMainClass());
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

    private static class InstrumentedClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        InstrumentedClassLoader(Map<String, byte[]> definitions) {
            super(TestFiberTransformer.class.getClassLoader());
            this.definitions = definitions;
        }

        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return define(bytes);
        }
    }
}
