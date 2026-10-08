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
