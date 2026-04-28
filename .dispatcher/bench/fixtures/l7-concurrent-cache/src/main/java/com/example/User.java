package com.example;

import java.util.Objects;

public class User {

    private final String id;
    private final String name;
    private final String email;
    private final Role role;

    public enum Role {
        ADMIN, USER, GUEST
    }

    public User(String id, String name, String email, Role role) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email must not be blank");
        if (role == null) throw new IllegalArgumentException("role must not be null");
        this.id = id;
        this.name = name;
        this.email = email;
        this.role = role;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "User{id='%s', name='%s', email='%s', role=%s}".formatted(id, name, email, role);
    }
}
