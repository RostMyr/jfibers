package com.github.rostmyr.jfibers.instrumentation;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.Files.createDirectories;

/**
 * Rostyslav Myroshnychenko
 * on 03.06.2018.
 */
public class FiberInstrumentator {
    private static final Map<ClassLoader, CustomClassLoader> CUSTOM_LOADERS_BY_DEFAULT = new HashMap<>();
    private static final Path CUSTOM_CLASSES_DIR;

    static {
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir")).resolve("cc");
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
            CUSTOM_CLASSES_DIR = Files.createDirectory(dir);
            addURL(new URL("file:/" + CUSTOM_CLASSES_DIR.toAbsolutePath().toString()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

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
        instrumentation.addTransformer((loader, className, classBeingRedefined, protectionDomain, classfileBuffer) -> {
            FiberTransformer fiberTransformer = new FiberTransformer(classfileBuffer, false);
            try {
                FiberTransformerResult instrument = fiberTransformer.instrument();
                byte[] mainClass = instrument.getMainClass();
                if (mainClass == null) {
                    return null;
                }

                CustomClassLoader ccl =
                    CUSTOM_LOADERS_BY_DEFAULT.computeIfAbsent(loader, CustomClassLoader::new);

                String prefix = className.substring(0, className.lastIndexOf("/") + 1);
                for (Map.Entry<String, byte[]> contentByName : instrument.getFibers().entrySet()) {
                    Files.write(
                        createDirectories(CUSTOM_CLASSES_DIR.resolve(prefix)).resolve(contentByName.getKey() + ".class"),
                        contentByName.getValue()
                    );
                }
                instrument.getFibers().forEach((name, content) -> ccl.define(prefix + name, content));
                return mainClass;
            } catch (IOException e) {
                throw new RuntimeException("Error during runtime class instrumentation", e);
            }
        }, true);
    }

    private static class CustomClassLoader extends URLClassLoader {
        CustomClassLoader(ClassLoader parent) {
            super(new URL[0], parent);

        }

        Class<?> define(String name, byte[] content) {
            return defineClass(null, content, 0, content.length);
        }
    }

    private static void addURL(URL u) throws IOException {
        URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        Class sysclass = URLClassLoader.class;

        try {
            Method method = sysclass.getDeclaredMethod("addURL", new Class[]{URL.class});
            method.setAccessible(true);
            method.invoke(sysloader, new Object[]{u});
        } catch (Throwable t) {
            t.printStackTrace();
            throw new IOException("Error, could not add URL to system classloader");
        }
    }
}
