package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

public class TestFiberValidation {
    @Test
    public void shouldExplainUnsupportedMonitors() {
        assertThatThrownBy(() -> FiberTransformer.instrument(Monitor.class, false))
            .isInstanceOf(FiberInstrumentationException.class)
            .hasMessageContaining("Monitor#value").hasMessageContaining("synchronized");
    }

    @Test
    public void shouldRejectMarkersInMethodsWithTheWrongReturnType() {
        assertThatThrownBy(() -> FiberTransformer.instrument(WrongReturn.class, false))
            .isInstanceOf(FiberInstrumentationException.class)
            .hasMessageContaining("WrongReturn#value").hasMessageContaining("must return Fiber");
    }

    @Test
    public void shouldWorkWithoutDebugInformation() throws Exception {
        ClassWriter stripped = new ClassWriter(0);
        new ClassReader(TestFiberModel.class.getName()).accept(stripped, ClassReader.SKIP_DEBUG);
        Class<?> type = InstrumentedTestModel.load(TestFiberModel.class,
            FiberTransformer.instrument(stripped.toByteArray(), false));
        Fiber<?> fiber = (Fiber<?>) type.getMethod("callFiber").invoke(type.getConstructor().newInstance());
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        manager.run();
        assertThat(fiber.getResult()).isEqualTo("AB");
    }

    @Test
    public void shouldRejectSuspensionInsideAConstructorArgument() {
        assertThatThrownBy(() -> FiberTransformer.instrument(ConstructorArgument.class, false))
            .isInstanceOf(FiberInstrumentationException.class)
            .hasMessageContaining("ConstructorArgument#value").hasMessageContaining("constructor");
    }

    @Test
    public void shouldRunControlFlowWithoutDebugInformation() throws Exception {
        ClassWriter stripped = new ClassWriter(0);
        new ClassReader(ControlFlowModel.class.getName()).accept(stripped, ClassReader.SKIP_DEBUG);
        Class<?> type = InstrumentedTestModel.load(ControlFlowModel.class,
            FiberTransformer.instrument(stripped.toByteArray(), false));
        Fiber<?> fiber = (Fiber<?>) type.getMethod("loop").invoke(type.getConstructor().newInstance());
        FiberManager manager = new FiberManager();
        manager.schedule(fiber);
        manager.run();
        assertThat(fiber.getException()).isNull();
        assertThat(fiber.getResult()).isEqualTo(35);
    }

    @Test
    public void shouldFailClearlyWithoutInstrumentation() {
        assertThatThrownBy(() -> new TestFiberModel().fiberWithImmediateReturn())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("without instrumentation");
    }

    public static class Monitor {
        public synchronized Fiber<String> value() {
            return Fiber.result("invalid");
        }
    }

    public static class WrongReturn {
        public String value() {
            return Fiber.call("oops");
        }
    }

    public static class ConstructorArgument {
        public Fiber<String> value() {
            return Fiber.result(new String(Fiber.call("text")));
        }
    }
}
