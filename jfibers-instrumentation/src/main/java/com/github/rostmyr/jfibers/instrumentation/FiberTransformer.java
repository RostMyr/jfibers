package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;

import static org.objectweb.asm.ClassReader.SKIP_FRAMES;
import static org.objectweb.asm.Opcodes.*;

/**
 * Rostyslav Myroshnychenko
 * on 11.06.2018.
 */
public class FiberTransformer {

    /**
     * Instruments the given class
     *
     * @param clazz class
     * @param debug debug enabled
     */
    public static FiberTransformerResult instrument(Class<?> clazz, boolean debug) throws IOException {
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        try (InputStream bytes = clazz.getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IOException("Class bytes not found: " + clazz.getName());
            }
            return instrument(new ClassReader(bytes), debug, clazz.getClassLoader());
        }
    }

    /**
     * Instruments the given class
     *
     * @param clazzBytes class as bytes array
     * @param debug      debug enabled
     */
    public static FiberTransformerResult instrument(byte[] clazzBytes, boolean debug) throws IOException {
        return instrument(clazzBytes, debug, FiberTransformer.class.getClassLoader());
    }

    public static FiberTransformerResult instrument(byte[] clazzBytes, boolean debug, ClassLoader loader) throws IOException {
        return instrument(new ClassReader(clazzBytes), debug, loader);
    }

    private static FiberTransformerResult instrument(ClassReader cr, boolean debug, ClassLoader loader) throws IOException {
        ClassNode owner = new ClassNode();
        cr.accept(owner, SKIP_FRAMES);
        FiberValidation.validate(owner);
        var methods = owner.methods.stream().filter(FiberValidation::hasMarkers).toList();
        FiberTransformerResult result = new FiberTransformerResult();
        if (methods.isEmpty()) {
            return result;
        }
        ClassHierarchy hierarchy = new ClassHierarchy(loader, owner);
        java.util.Map<MethodNode, String> names = new java.util.LinkedHashMap<>();
        for (MethodNode method : methods) {
            long overloads = methods.stream().filter(other -> other.name.equals(method.name)).count();
            String suffix = overloads > 1 ? "$" + descriptorId(method.desc) : "";
            String name = owner.name + "$" + method.name + "_Fiber" + suffix;
            names.put(method, name);
            hierarchy.generated(name);
        }
        for (MethodNode method : methods) {
            String name = names.get(method);
            byte[] generated = new FiberMethodCompiler(owner, method, name, hierarchy).compile();
            FiberValidation.verify(generated);
            result.addFiber(name.substring(name.lastIndexOf('/') + 1), generated);
        }
        ClassWriter writer = hierarchy.writer();
        owner.accept(writer);
        byte[] main = writer.toByteArray();
        FiberValidation.verify(main);
        if (debug) {
            new ClassReader(main).accept(new org.objectweb.asm.util.TraceClassVisitor(
                new java.io.PrintWriter(System.out, true, java.nio.charset.StandardCharsets.UTF_8)), 0);
        }
        result.setMainClass(main);
        return result;
    }

    private static String descriptorId(String descriptor) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // A transformer exception alone is ignored by the JVM. Reject calls explicitly instead.
    static byte[] reject(byte[] original, String reason) {
        ClassNode owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        for (MethodNode method : owner.methods) {
            if (!FiberValidation.hasMarkers(method)) {
                continue;
            }
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            method.instructions.add(new TypeInsnNode(NEW, "java/lang/IllegalStateException"));
            method.instructions.add(new InsnNode(DUP));
            method.instructions.add(new LdcInsnNode("Fiber instrumentation failed: " + reason));
            method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false));
            method.instructions.add(new InsnNode(ATHROW));
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

}
