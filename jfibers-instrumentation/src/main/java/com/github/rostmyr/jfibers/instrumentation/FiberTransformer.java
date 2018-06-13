package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;

import static org.objectweb.asm.ClassReader.SKIP_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;

/**
 * Rostyslav Myroshnychenko
 * on 11.06.2018.
 */
public class FiberTransformer {
    private Class<?> clazz;
    private byte[] clazzBytes;
    private final boolean debug;

    public FiberTransformer(Class<?> clazz, boolean debug) {
        this.clazz = clazz;
        this.debug = debug;
    }

    public FiberTransformer(byte[] clazz, boolean debug) {
        this.clazzBytes = clazz;
        this.debug = debug;
    }

    public FiberTransformerResult instrument() throws IOException {
        FiberTransformerResult result = new FiberTransformerResult();
        ClassWriter cw = new ClassWriter(COMPUTE_FRAMES);
        FiberClassNodeAdapter cv = new FiberClassNodeAdapter(cw, debug, result);
        ClassReader cr = clazz == null ? new ClassReader(clazzBytes) : new ClassReader(clazz.getName());
        cr.accept(cv, SKIP_FRAMES);

        if (cv.isInstrumented()) {
            result.setMainClass(cw.toByteArray());
        }
        return result;
    }
}
