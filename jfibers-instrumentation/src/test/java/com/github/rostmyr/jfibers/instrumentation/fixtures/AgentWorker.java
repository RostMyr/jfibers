package com.github.rostmyr.jfibers.instrumentation.fixtures;

import com.github.rostmyr.jfibers.Fiber;

import java.util.concurrent.CompletableFuture;

public class AgentWorker {
    public Fiber<String> echo(int value) {
        return Fiber.result(Integer.toString(value));
    }

    public Fiber<String> echo(String value) {
        return Fiber.result(value);
    }

    public Fiber<String> future() {
        return Fiber.result(CompletableFuture.completedFuture("future"));
    }

    public Fiber<String> failedChild() {
        return Fiber.result(CompletableFuture.<String>failedFuture(new IllegalArgumentException("agent failure")));
    }

    public Fiber<String> failure() {
        return Fiber.result(failedChild());
    }
}
