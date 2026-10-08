package com.github.rostmyr.jfibers.instrumentation.fixtures;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class AgentApplication {
    private static final String WORKER = "com.github.rostmyr.jfibers.instrumentation.fixtures.AgentWorker";

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "system":
                verify(Class.forName(WORKER));
                break;
            case "custom":
                verifyCustomLoader(args[1]);
                break;
            case "parallel":
                CompletableFuture<Void> first = CompletableFuture.runAsync(() -> verifyUnchecked(args[1]));
                CompletableFuture<Void> second = CompletableFuture.runAsync(() -> verifyUnchecked(args[1]));
                CompletableFuture.allOf(first, second).join();
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
        System.out.println("agent scenario passed: " + args[0]);
    }

    private static void verifyUnchecked(String classes) {
        try {
            verifyCustomLoader(classes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void verifyCustomLoader(String classes) throws Exception {
        try (URLClassLoader loader = new WorkerClassLoader(Path.of(classes).toUri().toURL())) {
            verify(Class.forName(WORKER, true, loader));
        }
    }

    private static void verify(Class<?> type) throws Exception {
        Object worker = type.getConstructor().newInstance();
        Fiber<?> integer = (Fiber<?>) type.getMethod("echo", int.class).invoke(worker, 42);
        Fiber<?> string = (Fiber<?>) type.getMethod("echo", String.class).invoke(worker, "hello");
        Fiber<?> future = (Fiber<?>) type.getMethod("future").invoke(worker);
        Fiber<?> failure = (Fiber<?>) type.getMethod("failure").invoke(worker);
        FiberManager manager = new FiberManager();
        manager.schedule(integer);
        manager.schedule(string);
        manager.schedule(future);
        manager.schedule(failure);
        manager.run();

        require(Objects.equals(integer.getResult(), "42"), "integer overload result");
        require(Objects.equals(string.getResult(), "hello"), "string overload result");
        require(Objects.equals(future.getResult(), "future"), "future result");
        require(failure.getException() != null, "child failure propagation");
        require(manager.getSize() == 0, "queue drained");
        require(integer.getClass() != string.getClass(), "overload class names are distinct");
        require(integer.getClass().getClassLoader() == type.getClassLoader(), "defining loader preserved");
        require(Objects.equals(integer.getClass().getProtectionDomain().getCodeSource(),
            type.getProtectionDomain().getCodeSource()), "protection domain preserved");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static class WorkerClassLoader extends URLClassLoader {
        WorkerClassLoader(URL classes) {
            super(new URL[] {classes}, AgentApplication.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(WORKER) && !name.startsWith(WORKER + "$")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
