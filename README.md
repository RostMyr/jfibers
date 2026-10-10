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

To run the example after building:

```sh
java -cp 'jfibers-example/target/classes:jfibers-example/target/lib/*' com.github.rostmyr.jfibers.example.Application
```

Use `;` instead of `:` as the classpath separator on Windows. The example prints
the user's ID, updated phone number, and data, then stops its scheduler.

The build compiles to Java 27 bytecode, so the resulting artifacts also require
Java 27 or newer. Fiber methods require line numbers and local variable metadata;
the parent POM enables both. Consumers must also compile with this debug metadata.

## Performance Benchmarks

The optional [benchmark module](jfibers-benchmarks/README.md) compares the current
build-time-instrumented fibers with virtual threads and `CompletableFuture`.
It measures batch completion time, allocations, idle CPU, retained Java heap,
and propagation of cancellation from the input futures.

```sh
./mvnw -Pbenchmarks -pl jfibers-benchmarks -am clean verify
python3 jfibers-benchmarks/run.py
```

## Scheduling and Failures

Build-time instrumentation is the recommended integration. Unsupported fiber
methods fail the build with the class, method descriptor and source location.
The current validated subset is linear instance methods with debug metadata,
one terminal `result`/`nothing`, and awaited values assigned to reference locals.
Branches, loops, exception handlers, static/synchronized methods and reused local
slots are rejected. Generated methods are checked by ASM before class files are
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
and `result(child)`, aborting the caller. Future failures retain their
`ExecutionException` wrapper; its cause is the original failure. Interrupted
waits preserve the thread's interrupt flag. An unchecked exception in `update()`
fails that fiber while allowing other scheduled fibers to continue.

## Runtime Java Agent

For runtime instrumentation, compile your application's fiber methods with
debug metadata and launch it with the assembled agent JAR:

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

#### After Instrumentation
```
public class MyClass {
  private UserService userService;
  
  public Fiber<User> updateUserPhone(long userId, String phone) {
    return new updateUserPhone_Fiber(userId, phone);
  }
  
  protected int updateUserPhone_FiberUpdate(updateUserPhone_Fiber fiber) {
    switch(fiber.getState()) {
      TODO
    }
  }
  
  public class updateUserPhone_Fiber extends Fiber<User> {
    public long userId;
    public String phone;

    public updateUserPhone_Fiber(long userId, String phone) {
      this.userId = userId;
      this.phone = phone;
    }

    public int update() {
      return MyClass.this.updateUserPhone_FiberUpdate(this);
    }
  }
}
```

## Inspired By
http://ssw.jku.at/General/Staff/LS/coro/  
https://github.com/vsilaev/tascalate-javaflow  
https://www.youtube.com/watch?v=_Z8R9NmH0i4  
