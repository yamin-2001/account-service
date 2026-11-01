package com.banking.account.messaging;

import java.util.Arrays;

public final class CommandValidation {

    private CommandValidation() {}

    public static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new InvalidEventException(field + " is missing or too long");
        }
        return value.trim();
    }

    public static Long required(Long value, String field) {
        if (value == null) {
            throw new InvalidEventException(field + " is missing");
        }
        return value;
    }

    public static String oneOf(String value, String field, String... allowed) {
        if (value == null || Arrays.stream(allowed).noneMatch(value.trim()::equals)) {
            throw new InvalidEventException(field + " must be one of " + String.join(", ", allowed));
        }
        return value.trim();
    }
}