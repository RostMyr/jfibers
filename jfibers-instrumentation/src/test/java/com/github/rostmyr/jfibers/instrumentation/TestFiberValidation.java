package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFiberValidation {
    @Test
    public void shouldExplainUnsupportedControlFlow() {
        assertThatThrownBy(() -> FiberTransformer.instrument(Branch.class, false))
            .isInstanceOf(FiberInstrumentationException.class)
            .hasMessageContaining("Branch#branch").hasMessageContaining("branches, loops");
    }

    @Test
    public void shouldRejectMarkersInMethodsWithTheWrongReturnType() {
        assertThatThrownBy(() -> FiberTransformer.instrument(WrongReturn.class, false))
            .isInstanceOf(FiberInstrumentationException.class)
            .hasMessageContaining("WrongReturn#value").hasMessageContaining("must return Fiber");
    }

    @Test
    public void shouldExplainMissingDebugInformation() throws Exception {
        ClassWriter stripped = new ClassWriter(0);
        new ClassReader(TestFiberModel.class.getName()).accept(stripped, ClassReader.SKIP_DEBUG);
        assertThatThrownBy(() -> FiberTransformer.instrument(stripped.toByteArray(), false))
            .isInstanceOf(FiberInstrumentationException.class).hasMessageContaining("javac -g");
    }

    @Test
    public void shouldFailClearlyWithoutInstrumentation() {
        assertThatThrownBy(() -> new TestFiberModel().fiberWithImmediateReturn())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("without instrumentation");
    }

    public static class Branch {
        public Fiber<String> branch(boolean flag) {
            if (flag) {
                return Fiber.result("yes");
            }
            return Fiber.result("no");
        }
    }

    public static class WrongReturn {
        public String value() {
            return Fiber.call("oops");
        }
    }
}
