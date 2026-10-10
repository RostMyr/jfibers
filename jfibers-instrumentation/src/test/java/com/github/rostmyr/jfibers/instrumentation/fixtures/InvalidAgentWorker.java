package com.github.rostmyr.jfibers.instrumentation.fixtures;

import com.github.rostmyr.jfibers.Fiber;

public class InvalidAgentWorker {
    public String invalid() {
        return Fiber.call("invalid");
    }
}
