package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/** Lowers a JVM control-flow graph to a resumable method, spilling live frame values at suspension. */
final class FiberMethodCompiler {
    private static final String FIBER = FiberValidation.FIBER;
    private static final Type OBJECT = Type.getType(Object.class);
    private final ClassNode owner;
    private final MethodNode original;
    private final String generated;
    private final ClassHierarchy hierarchy;
    private final int fiberSlot;
    private final int temporary;
    private final int argument;
    private final Map<String, Type> fields = new LinkedHashMap<>();
    private final List<Resume> resumes = new ArrayList<>();
    private final MethodNode update;
    private final Frame<BasicValue>[] frames;

    FiberMethodCompiler(ClassNode owner, MethodNode original, String generated, ClassHierarchy hierarchy) {
        this.owner = owner;
        this.original = original;
        this.generated = generated;
        this.hierarchy = hierarchy;
        fiberSlot = original.maxLocals;
        temporary = fiberSlot + 1;
        argument = fiberSlot + 3;
        update = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
            original.name + "_FiberUpdate", "(L" + generated + ";)I", null, null);
        try {
            frames = new SuspensionAnalyzer(owner, hierarchy).analyze(owner.name, original);
        } catch (AnalyzerException | RuntimeException e) {
            throw error("cannot analyze JVM control flow: " + e.getMessage(), e);
        }
    }

    byte[] compile() {
        AbstractInsnNode[] instructions = original.instructions.toArray();
        Map<LabelNode, LabelNode> labels = new LinkedHashMap<>();
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        LabelNode start = new LabelNode();
        update.instructions.add(start);
        for (int i = 0; i < instructions.length; i++) {
            AbstractInsnNode instruction = instructions[i];
            if (instruction instanceof FrameNode) {
                continue;
            }
            if (frames[i] != null && FiberValidation.marker(instruction)) {
                marker((MethodInsnNode) instruction, frames[i]);
            } else if (frames[i] != null && instruction.getOpcode() == ARETURN) {
                returnFiber(frames[i]);
            } else {
                update.instructions.add(instruction.clone(labels));
            }
        }
        for (TryCatchBlockNode block : original.tryCatchBlocks) {
            update.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end),
                labels.get(block.handler), block.type));
        }
        addDispatch(start);
        update.maxLocals = argument + 1;
        update.maxStack = Math.max(original.maxStack + 4, 8);
        owner.methods.add(update);
        replaceWithFactory();
        return generatedClass();
    }

    private void marker(MethodInsnNode call, Frame<BasicValue> frame) {
        AbstractInsnNode next = call.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        boolean directReturn = next != null && next.getOpcode() == ARETURN;
        if (call.name.equals("nothing")) {
            emit(ACONST_NULL);
            resultOnStack(directReturn);
            return;
        }
        Type input = Type.getArgumentTypes(call.desc)[0];
        boolean terminal = call.name.equals("result");
        if (input.equals(OBJECT)) {
            if (terminal) {
                resultOnStack(directReturn);
            } else {
                suspend(frame, null);
            }
        } else {
            suspend(frame, input);
            if (terminal) {
                resultOnStack(directReturn);
            }
        }
    }

    private void resultOnStack(boolean directReturn) {
        if (!directReturn) {
            // A result stored in a Java local must keep its own value if finally evaluates another marker.
            update.instructions.add(new MethodInsnNode(INVOKESTATIC, FIBER, "completed",
                "(Ljava/lang/Object;)L" + FIBER + ";", false));
            return;
        }
        fiber();
        emit(SWAP);
        invoke("resultLiteral", "(Ljava/lang/Object;)I");
        emit(POP);
        fiber(); // Keep Java's return/finally control flow; ARETURN handles this token.
    }

    private void returnFiber(Frame<BasicValue> frame) {
        LabelNode tail = new LabelNode();
        emit(DUP);
        fiber();
        update.instructions.add(new JumpInsnNode(IF_ACMPNE, tail));
        emit(POP);
        emit(ICONST_M1);
        emit(IRETURN);
        update.instructions.add(tail);
        suspend(frame, Type.getObjectType(FIBER));
        fiber();
        emit(SWAP);
        invoke("resultLiteral", "(Ljava/lang/Object;)I");
        emit(IRETURN);
    }

    private void suspend(Frame<BasicValue> frame, Type dependency) {
        validateFrame(frame);
        int state = resumes.size() + 1;
        int stackSize = frame.getStackSize() - (dependency == null ? 0 : 1);
        if (dependency != null) {
            update.instructions.add(new VarInsnNode(ASTORE, argument));
        }
        for (int i = stackSize - 1; i >= 0; i--) {
            Type type = frame.getStack(i).getType();
            storeStack(stackField(state, i, type), type);
        }
        saveLocals(frame);
        if (dependency != null) {
            fiber();
            update.instructions.add(new VarInsnNode(ALOAD, argument));
            invoke("awaitFor", "(" + dependency.getDescriptor() + ")I");
            emit(POP);
        }
        update.instructions.add(new LdcInsnNode(state));
        emit(IRETURN);
        LabelNode continuation = new LabelNode();
        update.instructions.add(continuation);
        resumes.add(new Resume(state, continuation, frame));
        fiber();
        invoke("checkCancellation", "()V");
        for (int i = 0; i < stackSize; i++) {
            Type type = frame.getStack(i).getType();
            loadField(stackField(state, i, type), type);
        }
        if (dependency != null) {
            fiber();
            invoke("resumeAwait", "()Ljava/lang/Object;");
        }
    }

    private void validateFrame(Frame<BasicValue> frame) {
        for (int i = 0; i < frame.getLocals(); i++) {
            if (SuspensionAnalyzer.uninitialized(frame.getLocal(i))) {
                throw error("cannot suspend while an object constructor is incomplete; await before constructing it", null);
            }
        }
        for (int i = 0; i < frame.getStackSize(); i++) {
            if (SuspensionAnalyzer.uninitialized(frame.getStack(i)) || frame.getStack(i).getType() == null) {
                throw error("cannot suspend inside constructor arguments; await before constructing the object", null);
            }
        }
    }

    private void saveLocals(Frame<BasicValue> frame) {
        for (int i = 0; i < frame.getLocals(); i++) {
            Type type = frame.getLocal(i).getType();
            if (type == null) {
                continue;
            }
            fiber();
            update.instructions.add(new VarInsnNode(type.getOpcode(ILOAD), i));
            update.instructions.add(new FieldInsnNode(PUTFIELD, generated, localField(i, type), storage(type).getDescriptor()));
        }
    }

    private void restoreLocals(InsnList code, Frame<BasicValue> frame) {
        for (int i = 0; i < frame.getLocals(); i++) {
            Type type = frame.getLocal(i).getType();
            if (type != null) {
                loadField(code, localField(i, type), type);
                code.add(new VarInsnNode(type.getOpcode(ISTORE), i));
            }
        }
    }

    private void addDispatch(LabelNode start) {
        InsnList dispatch = new InsnList();
        dispatch.add(new VarInsnNode(ALOAD, 0));
        dispatch.add(new VarInsnNode(ASTORE, fiberSlot));
        dispatch.add(new VarInsnNode(ALOAD, fiberSlot));
        dispatch.add(new MethodInsnNode(INVOKEVIRTUAL, FIBER, "getState", "()I", false));
        LabelNode invalid = new LabelNode();
        LabelNode[] cases = new LabelNode[resumes.size() + 1];
        for (int i = 0; i < cases.length; i++) {
            cases[i] = new LabelNode();
        }
        dispatch.add(new TableSwitchInsnNode(0, cases.length - 1, invalid, cases));
        dispatch.add(cases[0]);
        restoreLocals(dispatch, frames[0]);
        dispatch.add(new JumpInsnNode(GOTO, start));
        for (Resume resume : resumes) {
            dispatch.add(cases[resume.state]);
            restoreLocals(dispatch, resume.frame);
            dispatch.add(new JumpInsnNode(GOTO, resume.continuation));
        }
        dispatch.add(invalid);
        dispatch.add(new TypeInsnNode(NEW, "java/lang/IllegalStateException"));
        dispatch.add(new InsnNode(DUP));
        dispatch.add(new LdcInsnNode("Invalid fiber continuation: " + owner.name + "#" + original.name));
        dispatch.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        dispatch.add(new InsnNode(ATHROW));
        update.instructions.insert(dispatch);
    }

    private byte[] generatedClass() {
        ClassWriter writer = hierarchy.writer();
        writer.visit(owner.version, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC, generated, null, FIBER, null);
        writer.visitSource(owner.sourceFile, null);
        for (Map.Entry<String, Type> field : fields.entrySet()) {
            writer.visitField(ACC_PUBLIC | ACC_SYNTHETIC, field.getKey(), field.getValue().getDescriptor(), null, null).visitEnd();
        }
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", constructorDescriptor(), null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, FIBER, "<init>", "()V", false);
        int source = 1;
        for (int slot = 0; slot < frames[0].getLocals(); slot++) {
            Type type = frames[0].getLocal(slot).getType();
            if (type != null) {
                constructor.visitVarInsn(ALOAD, 0);
                constructor.visitVarInsn(type.getOpcode(ILOAD), source);
                constructor.visitFieldInsn(PUTFIELD, generated, localField(slot, type), storage(type).getDescriptor());
                source += type.getSize();
            }
        }
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "update", "()I", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESTATIC, owner.name, update.name, update.desc, false);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        MethodVisitor unwind = writer.visitMethod(ACC_PROTECTED, "supportsCancellationUnwind", "()Z", null, null);
        unwind.visitCode();
        unwind.visitInsn(ICONST_1);
        unwind.visitInsn(IRETURN);
        unwind.visitMaxs(0, 0);
        unwind.visitEnd();
        MethodVisitor clear = writer.visitMethod(ACC_PROTECTED, "clearFrame", "()V", null, null);
        clear.visitCode();
        for (Map.Entry<String, Type> field : fields.entrySet()) {
            if (ClassHierarchy.reference(field.getValue())) {
                clear.visitVarInsn(ALOAD, 0);
                clear.visitInsn(ACONST_NULL);
                clear.visitFieldInsn(PUTFIELD, generated, field.getKey(), field.getValue().getDescriptor());
            }
        }
        clear.visitInsn(RETURN);
        clear.visitMaxs(0, 0);
        clear.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void replaceWithFactory() {
        original.instructions.clear();
        original.tryCatchBlocks.clear();
        original.localVariables = null;
        original.visibleLocalVariableAnnotations = null;
        original.invisibleLocalVariableAnnotations = null;
        original.instructions.add(new TypeInsnNode(NEW, generated));
        original.instructions.add(new InsnNode(DUP));
        int slot = 0;
        if ((original.access & ACC_STATIC) == 0) {
            original.instructions.add(new VarInsnNode(ALOAD, slot++));
        }
        for (Type type : Type.getArgumentTypes(original.desc)) {
            original.instructions.add(new VarInsnNode(type.getOpcode(ILOAD), slot));
            slot += type.getSize();
        }
        original.instructions.add(new MethodInsnNode(INVOKESPECIAL, generated, "<init>", constructorDescriptor(), false));
        original.instructions.add(new InsnNode(ARETURN));
    }

    private String constructorDescriptor() {
        String receiver = (original.access & ACC_STATIC) == 0 ? "L" + owner.name + ";" : "";
        return "(" + receiver + original.desc.substring(1, original.desc.indexOf(')') + 1) + "V";
    }

    private String localField(int slot, Type type) {
        return field("local$" + slot + "$" + storage(type).getSort(), type);
    }

    private String stackField(int state, int slot, Type type) {
        return field("stack$" + state + "$" + slot, type);
    }

    private String field(String name, Type type) {
        fields.putIfAbsent(name, storage(type));
        return name;
    }

    private static Type storage(Type type) {
        return ClassHierarchy.reference(type) ? OBJECT : type;
    }

    private void storeStack(String field, Type type) {
        update.instructions.add(new VarInsnNode(type.getOpcode(ISTORE), temporary));
        fiber();
        update.instructions.add(new VarInsnNode(type.getOpcode(ILOAD), temporary));
        update.instructions.add(new FieldInsnNode(PUTFIELD, generated, field, storage(type).getDescriptor()));
    }

    private void loadField(String field, Type type) {
        loadField(update.instructions, field, type);
    }

    private void loadField(InsnList code, String field, Type type) {
        if (type.getInternalName().equals("null")) {
            code.add(new InsnNode(ACONST_NULL));
            return;
        }
        code.add(new VarInsnNode(ALOAD, fiberSlot));
        code.add(new FieldInsnNode(GETFIELD, generated, field, storage(type).getDescriptor()));
        if (ClassHierarchy.reference(type) && !type.equals(OBJECT)) {
            code.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
        }
    }

    private void fiber() {
        update.instructions.add(new VarInsnNode(ALOAD, fiberSlot));
    }

    private void invoke(String name, String descriptor) {
        update.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, FIBER, name, descriptor, false));
    }

    private void emit(int opcode) {
        update.instructions.add(new InsnNode(opcode));
    }

    private FiberInstrumentationException error(String reason, Throwable cause) {
        return new FiberInstrumentationException(FiberValidation.location(owner, original) + ": " + reason, cause);
    }

    private record Resume(int state, LabelNode continuation, Frame<BasicValue> frame) {
    }
}
