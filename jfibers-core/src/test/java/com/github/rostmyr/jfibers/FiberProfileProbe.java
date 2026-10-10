package com.github.rostmyr.jfibers;

import jdk.jfr.Recording;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Runs in a fresh JVM because the diagnostics switch is deliberately fixed at class initialization. */
public final class FiberProfileProbe {
    private FiberProfileProbe() {
    }

    public static void main(String[] args) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("com.github.rostmyr.jfibers.Scheduler").withoutStackTrace();
            recording.enable("com.github.rostmyr.jfibers.Await").withThreshold(Duration.ZERO).withoutStackTrace();
            recording.start();
            ownerBurst();
            externalWaits();
            recording.stop();
            recording.dump(Path.of(args[0]));
        }
    }

    private static void ownerBurst() {
        List<CompletableFuture<Integer>> inputs = new ArrayList<>();
        List<Fiber<Integer>> fibers = new ArrayList<>();
        try (FiberManager manager = new FiberManager()) {
            for (int i = 0; i < 64; i++) {
                CompletableFuture<Integer> input = new CompletableFuture<>();
                inputs.add(input);
                Fiber<Integer> fiber = waiting(input);
                fibers.add(fiber);
                manager.schedule(fiber);
            }
            manager.schedule(new Fiber<Void>() {
                @Override
                public int update() {
                    inputs.forEach(input -> input.complete(42));
                    return -1;
                }
            });
            manager.run();
            for (Fiber<Integer> fiber : fibers) {
                if (!Integer.valueOf(42).equals(fiber.getResult())) {
                    throw new AssertionError("Incorrect fiber result");
                }
            }
        }
    }

    private static void externalWaits() throws Exception {
        for (String kind : List.of("child", "cancel", "poll", "stop")) {
            CompletableFuture<Integer> input = new CompletableFuture<>();
            FutureTask<Integer> polled = new FutureTask<>(() -> 42);
            Fiber<Integer> child = waiting("poll".equals(kind) ? polled : input);
            Fiber<Integer> root = "child".equals(kind) ? new Fiber<>() {
                @Override
                public int update() {
                    return state == 0 ? awaitFor(child) : waitForFiberResult();
                }
            } : child;
            FiberManager manager = new FiberManager();
            manager.schedule(root);
            Thread runner = new Thread(manager::run, "profile-probe-owner");
            runner.setDaemon(true);
            runner.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (runner.getState() != Thread.State.WAITING && runner.getState() != Thread.State.TIMED_WAITING) {
                    if (System.nanoTime() >= deadline) {
                        throw new AssertionError("Owner did not park");
                    }
                    Thread.sleep(1);
                }
                switch (kind) {
                    case "child" -> input.complete(42);
                    case "cancel" -> root.cancel();
                    case "poll" -> polled.run();
                    case "stop" -> manager.stop();
                    default -> throw new AssertionError(kind);
                }
                runner.join(3000);
                if (runner.isAlive()) {
                    throw new AssertionError("Lost wakeup: " + kind);
                }
            } finally {
                manager.stop();
                runner.join(3000);
                if (!runner.isAlive()) {
                    manager.close();
                }
            }
        }
    }

    private static Fiber<Integer> waiting(Future<Integer> input) {
        return new Fiber<>() {
            @Override
            public int update() {
                return state == 0 ? awaitFor(input) : waitForFutureResult();
            }
        };
    }
}
