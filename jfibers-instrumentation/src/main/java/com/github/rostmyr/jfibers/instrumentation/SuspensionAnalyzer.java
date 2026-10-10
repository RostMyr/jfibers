package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import static org.objectweb.asm.Opcodes.*;

/** JVM data-flow frames, including uninitialized allocations that cannot be spilled. */
final class SuspensionAnalyzer extends Analyzer<BasicValue> {
    SuspensionAnalyzer(ClassNode owner, ClassHierarchy hierarchy) {
        super(new Types(owner, hierarchy));
    }

    @Override
    protected Frame<BasicValue> newFrame(int locals, int stack) {
        return new InitializingFrame(locals, stack);
    }

    @Override
    protected Frame<BasicValue> newFrame(Frame<? extends BasicValue> source) {
        return new InitializingFrame(source);
    }

    static boolean uninitialized(BasicValue value) {
        return value instanceof Allocation;
    }

    private static final class Types extends SimpleVerifier {
        private final ClassHierarchy hierarchy;

        Types(ClassNode owner, ClassHierarchy hierarchy) {
            super(ASM9, Type.getObjectType(owner.name),
                owner.superName == null ? null : Type.getObjectType(owner.superName),
                owner.interfaces.stream().map(Type::getObjectType).toList(), (owner.access & ACC_INTERFACE) != 0);
            this.hierarchy = hierarchy;
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            if (instruction.getOpcode() == NEW) {
                return new Allocation(Type.getObjectType(((TypeInsnNode) instruction).desc), instruction);
            }
            return super.newOperation(instruction);
        }

        @Override
        protected boolean isInterface(Type type) {
            return hierarchy.isInterface(type);
        }

        @Override
        protected Type getSuperClass(Type type) {
            return hierarchy.superType(type);
        }

        @Override
        protected boolean isAssignableFrom(Type target, Type value) {
            return hierarchy.assignable(target, value);
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EQ_OVERRIDING_EQUALS_NOT_SYMMETRIC",
        justification = "An uninitialized allocation must differ from an initialized value of the same JVM type")
    private static final class Allocation extends BasicValue {
        private final AbstractInsnNode instruction;

        Allocation(Type type, AbstractInsnNode instruction) {
            super(type);
            this.instruction = instruction;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Allocation allocation && instruction == allocation.instruction;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(instruction);
        }
    }

    private static final class InitializingFrame extends Frame<BasicValue> {
        InitializingFrame(int locals, int stack) {
            super(locals, stack);
        }

        InitializingFrame(Frame<? extends BasicValue> source) {
            super(source);
        }

        @Override
        public void execute(AbstractInsnNode instruction, Interpreter<BasicValue> interpreter) throws AnalyzerException {
            BasicValue receiver = null;
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")) {
                receiver = getStack(getStackSize() - Type.getArgumentTypes(call.desc).length - 1);
            }
            super.execute(instruction, interpreter);
            if (receiver instanceof Allocation) {
                BasicValue initialized = new BasicValue(receiver.getType());
                for (int i = 0; i < getLocals(); i++) {
                    if (receiver.equals(getLocal(i))) {
                        setLocal(i, initialized);
                    }
                }
                for (int i = 0; i < getStackSize(); i++) {
                    if (receiver.equals(getStack(i))) {
                        setStack(i, initialized);
                    }
                }
            }
        }
    }
}
