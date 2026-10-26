package com.banking.account.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    // Format: ACC-12345678 (8 digits, never starting with 0)
    public String next() {
        return "ACC-" + (10_000_000 + random.nextInt(90_000_000));
    }
}