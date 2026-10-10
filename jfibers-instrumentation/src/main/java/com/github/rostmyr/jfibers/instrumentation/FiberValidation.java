package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.HashSet;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

final class FiberValidation {
    static final String FIBER = Type.getInternalName(Fiber.class);

    private FiberValidation() {
    }

    static boolean marker(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
            && call.owner.equals(FIBER)
            && (call.name.equals("call") || call.name.equals("result") || call.name.equals("nothing"));
    }

    static boolean hasMarkers(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (marker(instruction)) {
                return true;
            }
        }
        return false;
    }

    static void validate(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (!hasMarkers(method)) {
                continue;
            }
            require(owner, method, Type.getReturnType(method.desc).equals(Type.getType(Fiber.class)),
                "methods containing markers must return Fiber");
            require(owner, method, (owner.access & ACC_INTERFACE) == 0, "interface methods are not supported");
            require(owner, method, (method.access & (ACC_STATIC | ACC_SYNCHRONIZED)) == 0,
                "static and synchronized fiber methods are not supported");
            require(owner, method, method.tryCatchBlocks.isEmpty(), "try/catch/finally is not supported yet");
            require(owner, method, method.localVariables != null && !method.localVariables.isEmpty(),
                "debug local-variable information is required; compile with javac -g");
            Set<Integer> slots = new HashSet<>();
            Set<String> names = new HashSet<>();
            int expectedSlot = 0;
            for (LocalVariableNode local : method.localVariables) {
                require(owner, method, local.index == expectedSlot && slots.add(local.index) && names.add(local.name),
                    "reused or scoped local-variable slots are not supported yet");
                expectedSlot += Type.getType(local.desc).getSize();
            }
            int terminals = 0;
            boolean lines = false;
            for (AbstractInsnNode instruction : method.instructions) {
                lines |= instruction instanceof LineNumberNode;
                require(owner, method, !(instruction instanceof JumpInsnNode)
                    && !(instruction instanceof TableSwitchInsnNode) && !(instruction instanceof LookupSwitchInsnNode),
                    "branches, loops and switches are not supported yet");
                int opcode = instruction.getOpcode();
                require(owner, method, opcode != MONITORENTER && opcode != MONITOREXIT,
                    "suspension inside a monitor is not supported");
                require(owner, method, !(instruction instanceof IincInsnNode)
                    && !(opcode >= IALOAD && opcode <= SALOAD), "increment and array-load instructions are not supported yet");
                if (marker(instruction)) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!call.name.equals("call")) {
                        terminals++;
                        require(owner, method, nextCode(instruction).getOpcode() == ARETURN,
                            "result/nothing must be returned directly");
                    } else if (!call.desc.startsWith("(Ljava/lang/Object;)")) {
                        AbstractInsnNode cast = nextCode(instruction);
                        require(owner, method, cast instanceof TypeInsnNode && cast.getOpcode() == CHECKCAST
                            && nextCode(cast).getOpcode() == ASTORE,
                            "awaited values must be assigned to a reference local variable");
                    }
                }
            }
            require(owner, method, lines, "debug line information is required; compile with javac -g");
            require(owner, method, terminals == 1, "exactly one terminal result/nothing is required");
        }
    }

    static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next == null ? new InsnNode(NOP) : next;
    }

    static void verify(byte[] bytes) {
        ClassNode owner = new ClassNode();
        new ClassReader(bytes).accept(owner, ClassReader.SKIP_FRAMES);
        for (MethodNode method : owner.methods) {
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
                continue;
            }
            try {
                new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
            } catch (AnalyzerException | RuntimeException failure) {
                throw new FiberInstrumentationException(location(owner, method)
                    + ": generated bytecode failed verification: " + failure.getMessage(), failure);
            }
        }
    }

    static String location(ClassNode owner, MethodNode method) {
        int line = -1;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
                break;
            }
        }
        return owner.name.replace('/', '.') + "#" + method.name + method.desc
            + " (" + owner.sourceFile + ":" + line + ")";
    }

    private static void require(ClassNode owner, MethodNode method, boolean valid, String reason) {
        if (!valid) {
            throw new FiberInstrumentationException(location(owner, method) + ": " + reason);
        }
    }
}
