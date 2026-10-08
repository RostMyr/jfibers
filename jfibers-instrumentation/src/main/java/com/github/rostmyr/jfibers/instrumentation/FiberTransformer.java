package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.io.InputStream;

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
        FiberTransformerResult result = new FiberTransformerResult();
        ClassWriter cw = createClassWriter(loader);
        FiberClassNodeAdapter cv = new FiberClassNodeAdapter(cw, debug, result, loader);
        cr.accept(cv, SKIP_FRAMES);

        if (cv.isInstrumented()) {
            result.setMainClass(cw.toByteArray());
        }
        return result;
    }

    static ClassWriter createClassWriter(ClassLoader loader) {
        return new ClassWriter(COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };
    }
}
