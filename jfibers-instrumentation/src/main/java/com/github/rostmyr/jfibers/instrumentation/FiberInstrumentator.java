package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

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
        Module javaBase = ClassLoader.class.getModule();
        Module agentModule = FiberInstrumentator.class.getModule();
        // Open only the package needed to define helpers in the application's loader.
        instrumentation.redefineModule(javaBase, Set.of(), Map.of(),
            Map.of("java.lang", Set.of(agentModule)), Set.of(), Map.of());
        try {
            MethodHandle classDefiner = MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
                .findVirtual(ClassLoader.class, "defineClass", MethodType.methodType(Class.class,
                    String.class, byte[].class, int.class, int.class, ProtectionDomain.class));
            instrumentation.addTransformer(new FiberClassTransformer(classDefiner));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot initialize fiber class definition", e);
        }
    }

    private static class FiberClassTransformer implements ClassFileTransformer {
        private final MethodHandle classDefiner;

        FiberClassTransformer(MethodHandle classDefiner) {
            this.classDefiner = classDefiner;
        }

        @Override
        public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
        ) {
            // Adding fiber classes and update methods is only valid at initial class definition.
            if (loader == null || classBeingRedefined != null) {
                return null;
            }
            try {
                FiberTransformerResult instrument = FiberTransformer.instrument(classfileBuffer, false, loader);
                byte[] mainClass = instrument.getMainClass();
                if (mainClass == null) {
                    return null;
                }

                for (byte[] content : instrument.getFibers().values()) {
                    defineClass(loader, content, protectionDomain);
                }
                return mainClass;
            } catch (IOException e) {
                throw new RuntimeException("Error during runtime class instrumentation", e);
            }
        }

        private Class<?> defineClass(ClassLoader loader, byte[] content, ProtectionDomain protectionDomain) throws IOException {
            String binaryName = new ClassReader(content).getClassName().replace('/', '.');
            try {
                return (Class<?>) classDefiner.invokeExact(loader, binaryName, content, 0, content.length, protectionDomain);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException("Cannot define generated fiber class " + binaryName, e);
            }
        }
    }
}
