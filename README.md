# jfibers

Simple coroutines/fibers implementations through bytecode instrumentation.

## Getting Started
TODO

## How It Works
**jfibers-maven-plugin** looks for and instruments all classes with methods which have `Fiber<T>` as a return type and methods body with either an instruction  
`INVOKEVIRTUAL com.github.rostmyr.jfibers.Fiber result (Fiber<T>/T/Future<T>)Fiber<T>`  
or `INVOKEVIRTUAL com.github.rostmyr.jfibers.Fiber nothing ()Fiber<Void>`.

### Here are a few examples

#### Before Instrumentation
```
public class MyClass {
  private UserService userService;
  
  public void Fiber<String> updateUserPhone(long userId, String phone) {
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
  
  public void Fiber<String> updateUserPhone(long userId, String phone) {
    User user = call(userService.getUser(userId));
    user.setPhone(phone);
    user = call(userService.saveUser(user));
    return result(user);
  }
  
  public class updateUserPhone_Fiber extends Fiber<String> {
    public long userId;
    public String phone;

    public callMethodChain_Fiber(long userId, String phone) {
      TODO
    }

    public int update() {
      return TestFiberModel.this.callMethodChain_FiberUpdate(this);
    }
  }
}
```

## Inspired By
http://ssw.jku.at/General/Staff/LS/coro/  
https://github.com/vsilaev/tascalate-javaflow  
https://www.youtube.com/watch?v=_Z8R9NmH0i4  
