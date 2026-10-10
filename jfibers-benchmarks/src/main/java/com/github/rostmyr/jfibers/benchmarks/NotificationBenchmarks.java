package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Notification costs without a timer floor; the sentinel runs after all consumers suspend. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
public class NotificationBenchmarks {
    public static final int BATCH_SIZE = 256;

    @Param({"OWNER", "EXTERNAL"})
    public String producer;
    private ExecutorService executor;
    private FiberManager manager;
    private FiberWorkload workload;

    @Setup(Level.Trial)
    public void setup() {
        executor = Executors.newSingleThreadExecutor();
        manager = new FiberManager();
        workload = new FiberWorkload();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.close();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public long notifications() {
        List<CompletableFuture<Integer>> inputs = new ArrayList<>(BATCH_SIZE);
        List<Fiber<Integer>> tasks = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            CompletableFuture<Integer> input = new CompletableFuture<>();
            inputs.add(input);
            Fiber<Integer> task = workload.increment(input);
            tasks.add(task);
            manager.schedule(task);
        }
        manager.schedule(new Fiber<Void>() {
            @Override
            public int update() {
                Runnable release = () -> inputs.forEach(input -> input.complete(41));
                if ("OWNER".equals(producer)) {
                    release.run();
                } else {
                    executor.execute(release);
                }
                return -1;
            }
        });
        manager.run();
        long sum = 0;
        for (Fiber<Integer> task : tasks) {
            if (!task.isReady() || task.getFailure() != null) {
                throw new IllegalStateException("Fiber did not complete successfully", task.getFailure());
            }
            sum += task.getResult();
        }
        if (sum != 42L * BATCH_SIZE) {
            throw new IllegalStateException("Incorrect result: " + sum);
        }
        return sum;
    }
}
