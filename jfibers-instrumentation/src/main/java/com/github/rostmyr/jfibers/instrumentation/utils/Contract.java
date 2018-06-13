package com.github.rostmyr.jfibers.instrumentation.utils;

import java.util.function.Supplier;

/**
 * Assertions
 *
 * Rostyslav Myroshnychenko
 * on 21.05.2018.
 */
public final class Contract {
    private Contract() {
        // util class
    }

    /**
     * Ensures the truth of an expression
     *
     * @param expression   a boolean expression
     * @param errorMessage exception message
     * @throws IllegalStateException if expression is false
     */
    public static void checkState(boolean expression, String errorMessage) {
        if (!expression) {
            throw new IllegalStateException(String.valueOf(errorMessage));
        }
    }

    /**
     * Ensures the object is not null
     *
     * @param object       an object to check for null
     * @param errorMessage exception message
     * @throws IllegalStateException if object is null
     */
    public static void checkState(Object object, String errorMessage) {
        if (object == null) {
            throw new IllegalStateException(errorMessage);
        }
    }

    /**
     * Ensures the object is not null
     *
     * @param object       an object to check for null
     * @param errorMessage a supplier which produces the exception message
     * @throws IllegalStateException if object is null
     */
    public static void checkState(Object object, Supplier<String> errorMessage) {
        if (object == null) {
            throw new IllegalStateException(errorMessage.get());
        }
    }

    /**
     * Ensures the truth of an expression
     *
     * @param expression   a boolean expression
     * @param errorMessage a supplier which produces the exception message
     * @throws IllegalStateException if expression is false
     */
    public static void checkArg(boolean expression, Supplier<String> errorMessage) {
        if (!expression) {
            throw new IllegalArgumentException(errorMessage.get());
        }
    }

    /**
     * Ensures the truth of an expression
     *
     * @param expression   a boolean expression
     * @param errorMessage exception message
     * @throws IllegalStateException if expression is false
     */
    public static void checkArg(boolean expression, String errorMessage) {
        if (!expression) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    /**
     * Ensures the object is not null
     *
     * @param object       object
     * @param errorMessage the exception message to use if the check fails
     * @throws IllegalStateException if expression is false
     */
    public static void checkArg(Object object, String errorMessage) {
        checkArg(object != null, errorMessage);
    }

    /**
     * Ensures that an method parameter is not null
     *
     * @param reference    an object reference
     * @param errorMessage the exception message to use if the check fails; will be converted to a
     *                     string using {@link String#valueOf(Object)}
     * @return the non-null reference that was validated
     * @throws NullPointerException if {@code reference} is null
     */
    public static <T> T checkNotNull(T reference, String errorMessage) {
        if (reference == null) {
            throw new NullPointerException(errorMessage);
        }
        return reference;
    }
}
