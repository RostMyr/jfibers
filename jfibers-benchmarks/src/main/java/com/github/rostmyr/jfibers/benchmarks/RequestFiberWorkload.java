package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static com.github.rostmyr.jfibers.Fiber.call;
import static com.github.rostmyr.jfibers.Fiber.nothing;

/** A fresh transformed root per accepted HTTP request, including suspension inside cleanup. */
public final class RequestFiberWorkload {
    private RequestFiberWorkload() {
    }

    static Fiber<Void> run(RequestTaskEngine.Request request) {
        if (!request.start()) {
            return nothing();
        }
        long checksum = 0;
        Throwable failure = null;
        try {
            for (int step = 0; step < 3; step++) {
                request.checkRunning();
                call(request.openStep());
                request.checkRunning();
                if (request.offload) {
                    Long value = call(request.computeAsync(step));
                    checksum += value;
                } else {
                    checksum += request.computeInline(step);
                }
            }
            request.checkRunning();
        } catch (Exception caught) {
            failure = caught;
        } finally {
            CompletableFuture<Void> cleanup = request.beginCleanup();
            boolean done = false;
            while (!done) {
                try {
                    call(cleanup);
                    done = true;
                } catch (CancellationException ignored) {
                    // Native cancellation is injected once. Retry the same uncancellable cleanup.
                }
            }
            request.bodyFinished(checksum, failure);
        }
        return nothing();
    }
}
