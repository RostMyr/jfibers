# jfibers

Simple coroutines/fibers implementations through bytecode instrumentation.

## Getting Started

Requires JDK 27 or newer. The Maven Wrapper pins Maven 3.10.0 and downloads it
on the first run; a separate Maven installation is optional. These are the
latest stable releases as of 8 October 2026. Java 27 is a non-LTS release.

Set `JAVA_HOME` to your JDK, then build all modules and run the tests:

```sh
./mvnw clean verify
```

On Windows, use `mvnw.cmd` instead. To include Checkstyle and SpotBugs reports:

```sh
./mvnw -Pcode-analysis clean verify
```

The analysis profile reports existing style warnings and potential bugs without
failing the build. Reports are written to each module's `target` directory.
The 10 October 2026 production report has five medium findings: shared failure and
dependency references and public-monitor synchronization in `Fiber`, plus two
existing exposure/suppression findings in `FiberTransformerResult`. Benchmark
analysis also reports JMH-generated fields; these reports are not a clean-analysis
gate.

To run the example after building:

```sh
java -cp 'jfibers-example/target/classes:jfibers-example/target/lib/*' com.github.rostmyr.jfibers.example.Application
```

Use `;` instead of `:` as the classpath separator on Windows. The example prints
the user's ID, updated phone number, and data, then stops its scheduler.

The build compiles to Java 27 bytecode, so the resulting artifacts also require
Java 27 or newer. Debug metadata improves source diagnostics, but is not required for instrumentation.
The parent POM keeps it enabled for useful stack traces.

## Performance Benchmarks

The optional [benchmark module](jfibers-benchmarks/README.md) compares the current
build-time-instrumented fibers with virtual threads and `CompletableFuture`.
It measures batch completion time, allocations, idle CPU, retained Java heap,
and propagation of cancellation from the input futures.

```sh
./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify
python3 jfibers-benchmarks/run.py
```

The [HTTP comparison](jfibers-benchmarks/HTTP.md) includes a virtual-thread
`sendAsync().get()` control. The scheduler harness compares runtime JARs using
the same benchmark artifact. [JFR diagnostics](PROFILING.md) expose scheduler
counters and sampled completion-to-resumption delay with `-Djfibers.jfr=true`;
they are disabled by default and do not require a runtime instrumentation agent.
The [optimization report](jfibers-benchmarks/OPTIMIZATION.md) includes raw
before/after measurements and the measured cost of enabling those diagnostics.

## Scheduling and Failures

Build-time instrumentation is the recommended integration. Unsupported fiber
methods fail the build with the class, method descriptor and source location.
The transformer supports instance and static methods, branches, loops, switches,
scoped/reused locals, primitive and reference values, nested expressions, and
`try/catch/finally` including try-with-resources. JVM data-flow analysis determines
which local and operand-stack values must survive suspension. Source line numbers
and local-variable tables do not determine execution semantics.

Interface fiber methods, synchronized methods/monitor blocks and suspension while
an object constructor is incomplete are rejected explicitly. For example, await a
constructor argument in a local variable before passing it to `new SomeType(...)`.
Blocking calls and loops without a `call` remain cooperative responsibilities.
Generated methods are checked by ASM before class files are
written. The Maven plugin validates the whole output directory before writing
any transformed class and resolves types on the project's compile classpath.

Calling a marker without instrumentation throws an explicit exception. If the
runtime agent rejects a class, calls to its fiber methods report the validation
error instead of falling back to uninstrumented marker execution.

`FiberManager.run()` updates ready fibers in round-robin order and returns when
all scheduled fibers complete or `stop()` is called. Waiting fibers leave the ready
queue; completion of a child or `CompletableFuture` makes them ready again.
When only notification-based waits remain, the scheduler parks without polling.
Schedule before `run()` or from its executing thread;
`stop()` may be called from another thread. A stop request takes effect between
updates, so `update()` must remain cooperative and return promptly. Stopping
does not cancel unfinished fibers, and a stopped manager cannot be restarted.
Scheduling a completed fiber or scheduling the same fiber twice is rejected.

A plain `Future` has no completion notification API. The compatibility fallback
checks such futures at most once per millisecond per waiting fiber, parking
between checks when idle. To use notifications instead, explicitly wrap it with
`FiberFutures.observe(future, executor)`. This runs blocking `get()` on the supplied
executor; a virtual-thread executor is suitable. The caller owns its executor and
source lifetime. Cancelling the adapter does not cancel the source or its waiter.

Failed and cancelled futures complete their fiber with an exception accessible
through `getException()`. Child failures propagate through both `call(child)`
and `result(child)`. Instrumented methods observe failures at the original await
instruction: surrounding catch/finally handlers execute normally. Uncaught failures
terminate the fiber. Future failures retain their
`ExecutionException` wrapper; its cause is the original failure. Interrupted
waits preserve the thread's interrupt flag. An unchecked exception in `update()`
fails that fiber while allowing other scheduled fibers to continue.

## Lifecycle, Cancellation and Timeouts

Use `getStatus()` (`NEW`, `RUNNABLE`, `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`,
`CANCELLED`, `TIMED_OUT`) and `isDone()` for application logic. `getState()` and
`setState()` remain as deprecated continuation APIs for existing generated and
handwritten fibers. Inspect results and failures after completion.

```java
try (FiberManager manager = new FiberManager()) {
    Fiber<User> task = service.loadUser().withTimeout(Duration.ofSeconds(2));
    manager.schedule(task);
    manager.run();
    // task.getStatus(), task.getResult(), task.getFailure()
}
```

`cancel()` is thread-safe and reports whether it accepted a new request. Before a
fiber starts, cancellation prevents entry into its body. After it starts, generated
code injects cancellation at the next suspension point, allowing catch/finally and
try-with-resources cleanup to run. Cleanup may itself await. A cancellation request
remains terminal even when caught: no success result is published; cleanup failures
are retained as suppressed exceptions. Handwritten fibers without the generated
unwind hook terminate between updates.

`withTimeout(Duration)` must be configured before scheduling. Its monotonic deadline
starts at scheduling; expiry uses the same cooperative cleanup path and produces
`TIMED_OUT` with a `TimeoutException`. No thread is interrupted. Neither cancellation
nor a deadline can preempt a blocking call or a loop that never yields, and cleanup
can extend beyond the deadline.

An unscheduled child first awaited by a parent belongs to that parent. Cancellation
propagates to owned children, and the parent waits for their cleanup before becoming
terminal. A previously scheduled dependency is shared: cancelling an observer does
not cancel it. External awaited futures are caller-owned and are never cancelled
implicitly. Dependency cycles are rejected.

`cancelAll()` requests cancellation at the next scheduler turn. `shutdown()` also
rejects new root tasks; `run()` drains cleanup. `close()` requests shutdown and waits
for cleanup, running the scheduler itself if needed (including after `stop()`). A
`close()` call from inside a fiber only requests shutdown so the scheduler can keep
executing cleanup. Completed fibers release captured references, wait registrations,
child links and deadlines. The result and failure remain available to callers.

`getFailure()` preserves the original throwable. `getException()` keeps the older
Exception-based API and wraps an uncaught Error in `FiberExecutionException`; a
parent's source handler still receives the original Error. Fatal VM errors escape
the scheduler.

## Runtime Java Agent

For runtime instrumentation, launch the application with the assembled agent JAR:

```sh
java -javaagent:jfibers-instrumentation/target/jfibers-instrumentation-1.0.0-SNAPSHOT-jar-with-dependencies.jar -cp YOUR_APPLICATION_CLASSPATH your.package.Main
```

Use build-time instrumentation or the runtime agent for a given application.
The agent defines generated fibers in the application's original ClassLoader
and ProtectionDomain, including when applications use custom loaders. It opens
`java.base/java.lang` to its own module through the Instrumentation API to access
class definition; an additional `--add-opens` flag is not needed. Runtime
instrumentation applies only when classes are first loaded. Dynamically attaching
the agent affects subsequent loads, not classes already loaded; retransformation
and bootstrap classes are not instrumented.

`./mvnw verify` also starts separate JVMs to test the packaged agent with system,
custom, and parallel custom loaders. Overloaded fiber methods receive distinct,
deterministic generated class names derived from their erased method descriptors.

## How It Works
**jfibers-maven-plugin** runs during `process-classes`. It finds methods returning
`Fiber<T>` that invoke the static markers `Fiber.result(...)` or `Fiber.nothing()`
and rewrites them as resumable state machines. `Fiber.call(...)` marks where a
method waits for another fiber or a future.

### Here are a few examples

#### Before Instrumentation
```
public class MyClass {
  private UserService userService;
  
  public Fiber<User> updateUserPhone(long userId, String phone) {
    User user = call(userService.getUser(userId));
    user.setPhone(phone);
    user = call(userService.saveUser(user));
    return result(user);
  }
}
```

#### Generated state machine

The public method constructs a fiber. A synthetic method in the original class
executes its body, preserving access to private members. At each `call`, generated
code stores JVM locals and operand-stack values in the fiber, returns to the
scheduler, and later restores them at that continuation. Exception tables and
branches retain their original source relationships. Terminal `result`/`nothing`
markers preserve Java's return/finally ordering, including a suspended cleanup.
Generated classes keep deterministic names and are verified before publication.

## Inspired By
http://ssw.jku.at/General/Staff/LS/coro/  
https://github.com/vsilaev/tascalate-javaflow  
https://www.youtube.com/watch?v=_Z8R9NmH0i4  
