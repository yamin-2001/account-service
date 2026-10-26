package com.banking.account.exception;

public class CbsUnavailableException extends RuntimeException {
    public CbsUnavailableException(String message) {
        super(message);
    }
}