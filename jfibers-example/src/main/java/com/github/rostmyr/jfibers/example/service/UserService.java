package com.github.rostmyr.jfibers.example.service;

import com.github.rostmyr.jfibers.Fiber;
import com.github.rostmyr.jfibers.example.repository.User;
import com.github.rostmyr.jfibers.example.repository.UserRepository;

import static com.github.rostmyr.jfibers.Fiber.result;

/**
 * Rostyslav Myroshnychenko
 * on 07.06.2018.
 */
public class UserService {
    private final UserRepository repository = new UserRepository();
    private long idSequence = 1L;

    public Fiber<User> getUser(long id) {
        return result(repository.getById(id));
    }

    public Fiber<Long> createUser(String firstName, String lastName) {
        long id = idSequence++;
        repository.save(new User(id, firstName, lastName));
        return result(id);
    }

    public Fiber<User> saveUser(User user) {
        repository.save(user);
        return result(user);
    }
}
