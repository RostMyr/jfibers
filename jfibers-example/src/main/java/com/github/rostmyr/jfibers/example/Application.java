package com.github.rostmyr.jfibers.example;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.FiberManager;
import com.github.rostmyr.jfibers.FiberManagers;
import com.github.rostmyr.jfibers.example.repository.User;
import com.github.rostmyr.jfibers.example.service.UserService;

import static com.github.rostmyr.jfibers.Fiber.call;
import static com.github.rostmyr.jfibers.Fiber.nothing;

/**
 * Rostyslav Myroshnychenko
 * on 02.06.2018.
 */
public class Application {
    private final UserService service = new UserService();

    public static void main(String[] args) {
        FiberManager fiberManager = FiberManagers.current();
        fiberManager.schedule(new Application().start());
        fiberManager.run();
    }

    public Fiber<Void> start() {
        Long id = call(service.saveUser("Ivan", "Ivanov"));
        System.out.println("New user id: " + id);

        User user = call(service.getUser(id));
        System.out.println("User data: " + user);

        return nothing();
    }
}
