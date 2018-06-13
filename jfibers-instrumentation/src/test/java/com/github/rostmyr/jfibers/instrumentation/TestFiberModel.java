package com.github.rostmyr.jfibers.instrumentation;

import com.github.rostmyr.jfibers.Fiber;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static com.github.rostmyr.jfibers.Fiber.*;

/**
 * Rostyslav Myroshnychenko
 * on 02.06.2018.
 */
public class TestFiberModel {
    private long sequence;

    public Fiber<Long> getSequence() {
        long id = sequence++;
        long result = id;
        return result(result);
    }

    public Fiber<String> callFiber() {
        String fiberResult = call(fiberWithSeveralCalls());
        return result(fiberResult);
    }

    public Fiber<String> callFuture() {
        String fiberResult = call(getFuture());
        return result(fiberResult);
    }

    public Fiber<Void> getNothing() {
        String fiberResult = call(getFuture());
        return nothing();
    }

    public Fiber<String> callFiberInReturn() {
        String fiberResult = call(getFuture());
        return result(fiberWithSeveralCalls());
    }

    public Fiber<String> callFutureInReturn() {
        return result(getFuture());
    }

    public Fiber<String> callFiberWithArgument() {
        String firstArg = "A";
        String fiberResult = call(callRegularMethod(firstArg, "B"));
        return result(fiberResult);
    }

    public Fiber<String> callRegularMethod(String second, String third) {
        String first = call(getString());
        return result(join(first, second));
    }

    public Fiber<String> callMethodChain() {
        String string = call(getString().concat("Chained Call!"));
        return result(string);
    }

    public Fiber<String> fiberWithSeveralCalls() {
        String first = call("A");
        String second = call("B");
        return result(join(first, second));
    }

    public Fiber<String> fiberWithStaticMethodCall() {
        String second = join("A", "B");
        return result(second);
    }

    public Fiber<String> fiberWithAssignment() {
        String first = "A";
        String second = join(first, "B");
        return result(first);
    }

    public Fiber<Integer> fiberWithImmediateReturn() {
        return result(1);
    }

//    public Fiber<String> callFiberTwice() {
//        String fiberResult = call(callRegularMethod(call(callFiber()), "b"));
//        return result(fiberResult);
//    }

//    public Fiber<Integer> loop() {
//        int sum = 0;
//        for (int i = 0; i < 2; i++) {
//            sum += call(i);
//        }
//        return result(sum);
//    }

    public String getString() {
        return "Hello World";
    }

    private Future<String> getFuture() {
        return CompletableFuture.completedFuture("A");
    }

    private static String join(String... string) {
        return String.join("", string);
    }
}
