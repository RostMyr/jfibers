package com.github.rostmyr.jfibers.example.repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Rostyslav Myroshnychenko
 * on 07.06.2018.
 */
public class UserRepository {
    private final Map<Long, User> usersByIds = new HashMap<>();

    public User getById(long id) {
        return usersByIds.get(id);
    }

    public void save(User user) {
        usersByIds.put(user.getId(), user);
    }
}
