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

//    public Fiber<Long> getFiberWithSequenceIncrement() {
//        long id = sequence++;
//        long result = id;
//        return result(result);
//    }
//
//    public Fiber<String> getFiberWithCallToAnotherFiber() {
//        String fiberResult = call(getWithWithFewCalls());
//        return result(fiberResult);
//    }
//
//    public Fiber<String> getFiberWithCallToFuture() {
//        String fiberResult = call(getFuture());
//        return result(fiberResult);
//    }
//
//    public Fiber<Void> getFiberWhichReturnNothing() {
//        String fiberResult = call(getFuture());
//        return nothing();
//    }
//
//    public Fiber<String> getFiberWithReturnCallToAnotherFiber() {
//        String fiberResult = call(getFuture());
//        return result(getWithWithFewCalls());
//    }
//
//    public Fiber<String> getFiberWithReturnCallToFuture() {
//        return result(getFuture());
//    }
//
//    public Fiber<String> getFiberWithCallToAnotherFiberWithArgs() {
//        String firstArg = "A";
//        String fiberResult = call(getFiberWithArgs(firstArg, "B"));
//        return result(fiberResult);
//    }
//
//    public Fiber<String> getFiberWithArgs(String second, String third) {
//        String first = call(getString());
//        return result(join(first, second));
//    }
//
//    public Fiber<String> getFiberWithChainedCall() {
//        String string = call(getString().concat("Chained Call!"));
//        return result(string);
//    }
//
//    public Fiber<String> getWithWithFewCalls() {
//        String first = call("A");
//        String second = call("B");
//        return result(join(first, second));
//    }
////
//    public Fiber<String> getFiberWithStaticMethodInvocation() {
//        String second = join("A", "B");
//        return result(second);
//    }
//
//    public Fiber<String> getFiberWithLocalVarAssignment() {
//        String first = "A";
//        String second = join(first, "B");
//        return result(first);
//    }
//
//    public Fiber<Integer> getFiberWithImmediateReturnStatement() {
//        return result(1);
//    }
//
//    public Fiber<Void> getFiberWithCallToAnotherFiberWithoutAssignment() {
//        call(getFiberWhichReturnNothing());
//        return nothing();
//    }
//
//    public Fiber<Void> getFiberWithCallToFutureWithoutAssignment() {
//        call(getFuture());
//        return nothing();
//    }

    public Fiber<Void> getFiberWithCatch() {
        try {
            String first = "A";
            String second = join(first, "B");
            System.out.println(second);
        } catch (Exception e) {
            System.out.println("Catch");
        } finally {
            System.out.println("Finally");
        }
        return nothing();
    }

//    public Fiber<String> callFiberTwice() {
//        String fiberResult = call(getFiberWithArgs(call(getFiberWithCallToAnotherFiber()), "b"));
//        return result(fiberResult);
//    }

//    public Fiber<Integer> loop() {
//        int sum = 0;
//        for (int i = 0; i < 2; i++) {
//            sum += call(i);
//        }
//        return result(sum);
//    }

    private String getString() {
        return "Hello World";
    }

    private Future<String> getFuture() {
        return CompletableFuture.completedFuture("A");
    }

    private static String join(String... string) {
        return String.join("", string);
    }
}
