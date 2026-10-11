package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberFutures;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.github.rostmyr.jfibers.Fiber.call;
import static com.github.rostmyr.jfibers.Fiber.nothing;

/** Real application-shaped methods, transformed by the project's Maven plugin. */
public final class ScenarioFiberWorkload {
    private ScenarioFiberWorkload() {
    }

    static Fiber<Void> worker(ScenarioProbe.Source source) {
        while (true) {
            ScenarioProbe.Job job = call(source.take());
            if (job == null) {
                return nothing();
            }
            long checksum = 0;
            Throwable failure = null;
            job.start();
            try {
                for (int step = 0; step < job.config.steps; step++) {
                    Integer value = call(job.openStep(step));
                    checksum += job.compute(value, step);
                }
            } catch (Exception caught) {
                failure = caught;
            } finally {
                job.finish(checksum, failure);
            }
        }
    }

    static Fiber<Void> workerOffload(ScenarioProbe.Source source, Executor executor) {
        while (true) {
            ScenarioProbe.Job job = call(source.take());
            if (job == null) {
                return nothing();
            }
            long checksum = 0;
            Throwable failure = null;
            job.start();
            try {
                for (int step = 0; step < job.config.steps; step++) {
                    Integer value = call(job.openStep(step));
                    if (job.heavy) {
                        int index = step;
                        CompletableFuture<Long> pending = FiberFutures.submit(() -> job.compute(value, index), executor);
                        try {
                            Long computed = call(pending);
                            checksum += computed;
                        } finally {
                            pending.cancel(true);
                        }
                    } else {
                        checksum += job.compute(value, step);
                    }
                }
            } catch (Exception caught) {
                failure = caught;
            } finally {
                job.finish(checksum, failure);
            }
        }
    }

    static Fiber<Void> waiting(MassWaitingProbe.Sample sample) {
        sample.entered.countDown();
        try {
            Integer value = call(sample.input);
            sample.complete(value + 1, null);
        } catch (Exception failure) {
            sample.complete(null, failure);
        }
        return nothing();
    }
}
