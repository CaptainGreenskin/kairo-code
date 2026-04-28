package com.example;

import java.util.regex.Pattern;

/**
 * Validates Member data.
 */
public class MemberValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    /**
     * Validates a member object.
     */
    public void validate(Member member) {
        if (member == null) {
            throw new IllegalArgumentException("Member must not be null");
        }
        if (member.getId() == null || member.getId().isBlank()) {
            throw new IllegalArgumentException("Member id must not be blank");
        }
        if (member.getName() == null || member.getName().isBlank()) {
            throw new IllegalArgumentException("Member name must not be blank");
        }
        if (!isValidEmail(member.getEmail())) {
            throw new IllegalArgumentException("Member email is invalid: " + member.getEmail());
        }
        if (!isValidMembership(member.getMembershipType())) {
            throw new IllegalArgumentException("Member membershipType is invalid");
        }
    }

    /**
     * Checks if an email has a basic valid format.
     */
    public boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Checks if membership type is valid (non-null).
     */
    public boolean isValidMembership(MembershipType type) {
        return type != null;
    }
}
