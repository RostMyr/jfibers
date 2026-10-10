package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
public class TaskBenchmarks {
    public static final int BATCH_SIZE = 256;

    @Param({"0", "1"})
    public int delayMillis;

    private ExecutorService virtualExecutor;
    private ScheduledExecutorService timer;
    private FiberManager manager;
    private FiberWorkload workload;

    @Setup(Level.Trial)
    public void setup() {
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        timer = Executors.newSingleThreadScheduledExecutor();
        manager = new FiberManager();
        workload = new FiberWorkload();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        timer.close();
        virtualExecutor.close();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public long jfibers() {
        List<CompletableFuture<Integer>> inputs = inputs();
        List<Fiber<Integer>> tasks = new ArrayList<>(BATCH_SIZE);
        for (CompletableFuture<Integer> input : inputs) {
            Fiber<Integer> task = workload.increment(input);
            tasks.add(task);
            manager.schedule(task);
        }
        release(inputs);
        manager.run();
        long sum = 0;
        for (Fiber<Integer> task : tasks) {
            if (!task.isReady() || task.getException() != null) {
                throw new IllegalStateException("Fiber did not complete successfully", task.getException());
            }
            sum += task.getResult();
        }
        return checked(sum);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public long virtualThreads() throws Exception {
        List<CompletableFuture<Integer>> inputs = inputs();
        List<Future<Integer>> tasks = new ArrayList<>(BATCH_SIZE);
        for (CompletableFuture<Integer> input : inputs) {
            tasks.add(virtualExecutor.submit(() -> input.get() + 1));
        }
        release(inputs);
        long sum = 0;
        for (Future<Integer> task : tasks) {
            sum += task.get();
        }
        return checked(sum);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public long completableFuture() {
        List<CompletableFuture<Integer>> inputs = inputs();
        List<CompletableFuture<Integer>> tasks = new ArrayList<>(BATCH_SIZE);
        for (CompletableFuture<Integer> input : inputs) {
            tasks.add(input.thenApply(value -> value + 1));
        }
        release(inputs);
        long sum = 0;
        for (CompletableFuture<Integer> task : tasks) {
            sum += task.join();
        }
        return checked(sum);
    }

    private List<CompletableFuture<Integer>> inputs() {
        List<CompletableFuture<Integer>> inputs = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            inputs.add(delayMillis == 0 ? CompletableFuture.completedFuture(41) : new CompletableFuture<>());
        }
        return inputs;
    }

    private void release(List<CompletableFuture<Integer>> inputs) {
        if (delayMillis != 0) {
            // Start the same external delay after all tasks have been submitted or registered.
            timer.schedule(() -> inputs.forEach(input -> input.complete(41)), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private long checked(long sum) {
        if (sum != 42L * BATCH_SIZE) {
            throw new IllegalStateException("Incorrect result: " + sum);
        }
        return sum;
    }
}
