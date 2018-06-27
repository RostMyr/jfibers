package com.github.rostmyr.jfibers.example.repository;

/**
 * Rostyslav Myroshnychenko
 * on 07.06.2018.
 */
public class User {
    private final long id;
    private final String firstName;
    private final String lastName;
    private String phone;

    public User(long id, String firstName, String lastName) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @Override
    public String toString() {
        return "User{"
            + "id=" + id
            + ", firstName='" + firstName + '\''
            + ", lastName='" + lastName + '\''
            + ", phone='" + phone + '\''
            + '}';
    }
}
