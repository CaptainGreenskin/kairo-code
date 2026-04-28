package com.example;

import java.util.Objects;

/**
 * Represents a library member.
 */
public class Member {
    private final String id;
    private final String name;
    private final String email;
    private final MembershipType membershipType;

    public Member(String id, String name, String email, MembershipType membershipType) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email must not be blank");
        if (membershipType == null) throw new IllegalArgumentException("membershipType must not be null");

        this.id = id;
        this.name = name;
        this.email = email;
        this.membershipType = membershipType;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public MembershipType getMembershipType() { return membershipType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Member member = (Member) o;
        return Objects.equals(id, member.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Member{id='%s', name='%s', type=%s}".formatted(id, name, membershipType);
    }
}
