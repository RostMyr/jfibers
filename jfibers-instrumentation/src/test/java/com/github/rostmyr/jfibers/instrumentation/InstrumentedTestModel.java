package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class InstrumentedTestModel extends ClassLoader {
    private final Map<String, byte[]> definitions;

    private InstrumentedTestModel(ClassLoader parent, Map<String, byte[]> definitions) {
        super(parent);
        this.definitions = definitions;
    }

    static Class<?> load(Class<?> template) throws IOException {
        FiberTransformerResult result = FiberTransformer.instrument(template, false);
        Map<String, byte[]> definitions = new HashMap<>();
        for (byte[] bytes : result.getFibers().values()) {
            definitions.put(new ClassReader(bytes).getClassName().replace('/', '.'), bytes);
        }
        return new InstrumentedTestModel(template.getClassLoader(), definitions).define(result.getMainClass());
    }

    private Class<?> define(byte[] bytes) {
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
