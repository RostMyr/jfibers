package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;
import java.net.http.HttpResponse;

import static com.github.rostmyr.jfibers.Fiber.call;
import static com.github.rostmyr.jfibers.Fiber.nothing;

/** A bounded client flow, compiled by the same Maven plugin as application fibers. */
public final class HttpFiberWorkload {
    private HttpFiberWorkload() {
    }

    static Fiber<Void> run(HttpSupport.Api api, HttpLoadProbe.Phase phase, HttpLoadProbe.WorkerStats stats) {
        while (phase.admit()) {
            long started = System.nanoTime();
            try {
                HttpResponse<byte[]> first = call(api.firstAsync());
                int token = HttpSupport.validate(first);
                HttpResponse<byte[]> second = call(api.secondAsync(token));
                HttpSupport.validate(second);
                stats.record(started, null, phase);
            } catch (Exception failure) {
                stats.record(started, failure, phase);
            }
        }
        return nothing();
    }
}
