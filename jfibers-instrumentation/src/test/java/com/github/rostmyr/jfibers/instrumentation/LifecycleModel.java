package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberFutures;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import static com.github.rostmyr.jfibers.Fiber.*;

public class LifecycleModel {
    public Future<Integer> input = new CompletableFuture<>();
    public Future<String> cleanup = CompletableFuture.completedFuture("child;");
    public StringBuilder events = new StringBuilder();
    public Fiber<Integer> lastChild;
    public Fiber<Integer> shared;
    public Callable<Integer> computation;
    public Executor executor;

    public Fiber<Integer> offload() {
        CompletableFuture<Integer> pending = FiberFutures.submit(computation, executor);
        try {
            return result(pending);
        } finally {
            pending.cancel(true);
            events.append("compute;");
        }
    }

    public Fiber<Integer> child() {
        try {
            int value = call(input);
            return result(value);
        } finally {
            events.append(call(cleanup));
        }
    }

    public Fiber<Integer> parent() {
        try {
            lastChild = child();
            return result(lastChild);
        } finally {
            events.append("parent;");
        }
    }

    public Fiber<Integer> observer() {
        try {
            return result(shared);
        } finally {
            events.append("observer;");
        }
    }

    public Fiber<Integer> caughtCancellation() {
        try {
            return result(input);
        } catch (Exception e) {
            return result(99);
        } finally {
            events.append("cleanup;");
        }
    }

    public Fiber<Integer> cleanupFailure() {
        try {
            return result(input);
        } finally {
            events.append("cleanup;");
            throw new IllegalStateException("cleanup failed");
        }
    }

    public Fiber<Integer> assertionFailure() {
        call("yield");
        throw new AssertionError("child assertion");
    }

    public Fiber<Integer> catchChildError() {
        try {
            return result(assertionFailure());
        } catch (AssertionError failure) {
            return result(42);
        }
    }
}
