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

    /**
     * Gets a user by id
     *
     * @param id user's id
     * @return a fiber with user
     */
    public Fiber<User> getUser(long id) {
        User user = repository.getById(id);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + id);
        }
        return result(user);
    }

    /**
     * Creates a new user with the specified name
     *
     * @param firstName user's first name
     * @param lastName  user's last name
     * @return a fiber with user id
     */
    public Fiber<Long> createUser(String firstName, String lastName) {
        long id = idSequence++;
        repository.save(new User(id, firstName, lastName));
        return result(id);
    }

    /**
     * Saves a given user
     *
     * @param user user to be saved
     * @return a fiber with user
     */
    public Fiber<User> saveUser(User user) {
        repository.save(user);
        return result(user);
    }
}
