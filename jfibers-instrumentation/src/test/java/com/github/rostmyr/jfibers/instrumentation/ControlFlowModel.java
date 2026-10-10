package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static com.github.rostmyr.jfibers.Fiber.*;

public class ControlFlowModel {
    public Future<Integer> input = CompletableFuture.completedFuture(10);
    public boolean flag = true;
    public StringBuilder events = new StringBuilder();

    public Fiber<Integer> branch() {
        if (flag) {
            int value = call(input);
            return result(value + 1);
        }
        return result(-1);
    }

    public Fiber<Integer> loop() {
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            if (i == 1) {
                continue;
            }
            sum += call(input) + i;
            if (i == 3) {
                break;
            }
        }
        return result(sum);
    }

    public Fiber<String> switches() {
        int choice = call(input);
        String value;
        switch (choice) {
            case 9: value = "nine"; break;
            case 10: value = "ten"; break;
            case 11: value = "eleven"; break;
            default: value = "other";
        }
        switch (value) {
            case "ten": return result(call("TEN"));
            case "nine": return result("NINE");
            default: return result(value);
        }
    }

    public Fiber<Integer> nestedExpressions() {
        return result(Math.max(5, call(input)) + call(CompletableFuture.completedFuture(2)));
    }

    public Fiber<Long> wideStack() {
        return result(9_000_000_001L + call(input));
    }

    public Fiber<Double> wideLocals() {
        long first = 9_000_000_001L;
        double second = 0.5;
        long third = call(input);
        return result(first + second + third);
    }

    public Fiber<String> scopedLocals() {
        if (flag) {
            int value = call(input);
            events.append(value);
        }
        {
            String value = call("end");
            events.append(value);
        }
        return result(events.toString());
    }

    public Fiber<Integer> arrays() {
        int[] values = {1, 2, 3};
        values[1] += call(input);
        return result(values[1]);
    }

    public Fiber<String> mergedTypes() {
        List<String> values;
        if (flag) {
            values = new ArrayList<>();
        } else {
            values = new LinkedList<>();
        }
        values.add(call("ready"));
        return result(values.getFirst());
    }

    public static Fiber<Integer> staticMethod() {
        return result(call(CompletableFuture.completedFuture(42)));
    }

    public Fiber<String> caughtFailure() {
        try {
            int value = call(input);
            return result("value=" + value);
        } catch (Exception e) {
            events.append("caught;");
            return result(call("recovered"));
        } finally {
            events.append("finally;");
        }
    }

    public Fiber<Integer> finallySuccess() {
        try {
            return result(input);
        } finally {
            events.append("finally;");
        }
    }

    public Fiber<Integer> finallyFailure() {
        try {
            return result(input);
        } finally {
            events.append("finally;");
        }
    }

    public Fiber<Integer> awaitInFinally() {
        try {
            return result(7);
        } finally {
            events.append(call(CompletableFuture.completedFuture("cleanup;")));
        }
    }

    public Fiber<String> nestedFinally() {
        try {
            try {
                call(input);
            } finally {
                events.append("inner;");
            }
            return result("ok");
        } catch (Exception e) {
            return result("recovered");
        } finally {
            events.append("outer;");
        }
    }

    public Fiber<Integer> resource() throws Exception {
        try (TestResource resource = new TestResource(events, true)) {
            return result(input);
        }
    }

    public Fiber<Integer> childCaught() {
        try {
            return result(finallyFailure());
        } catch (Exception e) {
            return result(99);
        }
    }

    public Fiber<Integer> directChild() {
        call("yield");
        return branch();
    }

    public Fiber<String> nullable() {
        String value = null;
        call(input);
        return result(value == null ? "null" : value);
    }

    public Fiber<Integer> separateResults() {
        Fiber<Integer> first = result(1);
        Fiber<Integer> second = result(2);
        call(second);
        return first;
    }

    public Fiber<Integer> discardedFinallyResult() {
        try {
            return result(1);
        } finally {
            result(2);
        }
    }

    public Fiber<Integer> overridingFinallyResult() {
        try {
            return result(1);
        } finally {
            return result(2);
        }
    }
}
