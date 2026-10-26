package com.banking.account.exception;

public class MaxAccountsReachedException extends RuntimeException {
    public MaxAccountsReachedException(String message) {
        super(message);
    }
}