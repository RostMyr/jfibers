package com.github.rostmyr.jfibers.benchmarks;

import com.github.rostmyr.jfibers.Fiber;

import java.util.concurrent.Future;

import static com.github.rostmyr.jfibers.Fiber.call;
import static com.github.rostmyr.jfibers.Fiber.result;

/** The actual public Fiber API, transformed by the Maven plugin before packaging. */
public class FiberWorkload {
    public Fiber<Integer> increment(Future<Integer> input) {
        Integer value = call(input);
        return result(value + 1);
    }
}
