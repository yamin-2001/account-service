package com.banking.account.exception;

public class LedgerAccountConflictException extends RuntimeException {
    public LedgerAccountConflictException(String message) {
        super(message);
    }
}