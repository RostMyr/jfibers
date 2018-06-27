package com.github.rostmyr.jfibers.instrumentation;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;

import static java.security.AccessController.doPrivileged;

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
            FiberTransformerResult instrument = FiberTransformer.instrument(classfileBuffer, false);
            byte[] mainClass = instrument.getMainClass();
            if (mainClass == null) {
                return null;
            }

            FiberClassLoader classLoader =
                doPrivileged((PrivilegedAction<FiberClassLoader>) () -> new FiberClassLoader(getClassLoader(loader)));
            instrument.getFibers().forEach((name, content) -> classLoader.define(content));
            return mainClass;
        }

        protected ClassLoader getClassLoader(final ClassLoader classLoader) {
            return null != classLoader ? classLoader : ClassLoader.getSystemClassLoader();
        }
    }

    private static class FiberClassLoader extends ClassLoader {
        FiberClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(byte[] content) {
            return defineClass(null, content, 0, content.length);
        }
    }
}
