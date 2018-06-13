package com.github.rostmyr.jfibers.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private final UserService service = new UserService();

    public static void main(String[] args) {
        FiberManager fiberManager = FiberManagers.current();
        fiberManager.schedule(new Application().start());
        fiberManager.run();
    }

    private Fiber<Void> start() {
        Long id = call(service.saveUser("Ivan", "Ivanov"));
        log.info("User id '{}'", id);

        User user = call(service.getUser(id));
        log.info("User date '{}'", user);

        return nothing();
    }
}
