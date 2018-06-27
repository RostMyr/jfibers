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

    /**
     * Instruments the given class
     *
     * @param clazz class
     * @param debug debug enabled
     */
    public static FiberTransformerResult instrument(Class<?> clazz, boolean debug) throws IOException {
        return instrument(new ClassReader(clazz.getName()), debug);
    }

    /**
     * Instruments the given class
     *
     * @param clazzBytes class as bytes array
     * @param debug      debug enabled
     */
    public static FiberTransformerResult instrument(byte[] clazzBytes, boolean debug) throws IOException {
        return instrument(new ClassReader(clazzBytes), debug);
    }

    private static FiberTransformerResult instrument(ClassReader cr, boolean debug) throws IOException {
        FiberTransformerResult result = new FiberTransformerResult();
        ClassWriter cw = new ClassWriter(COMPUTE_FRAMES);
        FiberClassNodeAdapter cv = new FiberClassNodeAdapter(cw, debug, result);
        cr.accept(cv, SKIP_FRAMES);

        if (cv.isInstrumented()) {
            result.setMainClass(cw.toByteArray());
        }
        return result;
    }
}
