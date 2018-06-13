package com.github.rostmyr.jfibers.instrumentation;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Rostyslav Myroshnychenko
 * on 03.06.2018.
 */
public class FiberInstrumentator {

    /**
     * JVM hook to statically load the javaagent at startup.
     */
    public static void premain(String args, Instrumentation instrumentation) {
        setupInstrumentation(instrumentation);
    }

    /**
     * JVM hook to dynamically load javaagent at runtime.
     */
    public static void agentmain(String args, Instrumentation instrumentation) {
        setupInstrumentation(instrumentation);
    }

    private static void setupInstrumentation(Instrumentation instrumentation) {
        instrumentation.addTransformer(new FiberClassTransformer(), true);
    }

    private static class FiberClassTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
        ) {
            FiberTransformer fiberTransformer = new FiberTransformer(classfileBuffer, false);
            try {
                FiberTransformerResult instrument = fiberTransformer.instrument();
                byte[] mainClass = instrument.getMainClass();
                if (mainClass == null) {
                    return null;
                }

                FiberClassLoader classLoader = new FiberClassLoader(getClassLoader(loader));
                String prefix = className.substring(0, className.lastIndexOf("/") + 1);
                instrument.getFibers().forEach((name, content) -> classLoader.define(prefix + name, content));
                return mainClass;
            } catch (IOException e) {
                throw new RuntimeException("Error during runtime class instrumentation", e);
            }
        }

        protected ClassLoader getClassLoader(final ClassLoader classLoader) {
            return null != classLoader ? classLoader : ClassLoader.getSystemClassLoader();
        }
    }

    private static class FiberClassLoader extends ClassLoader {
        public FiberClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] content) {
            return defineClass(null, content, 0, content.length);
        }
    }
}
