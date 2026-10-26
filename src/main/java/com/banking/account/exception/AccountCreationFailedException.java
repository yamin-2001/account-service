package com.banking.account.exception;

public class AccountCreationFailedException extends RuntimeException {
    public AccountCreationFailedException(String message) {
        super(message);
    }
}