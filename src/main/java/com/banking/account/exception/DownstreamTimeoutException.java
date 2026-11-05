package com.banking.account.exception;


public class DownstreamTimeoutException extends DownstreamException {

    public DownstreamTimeoutException(String target, String operation, String message, Throwable cause) {
        super(DownstreamFailure.TIMEOUT.defaultStatus(), DownstreamFailure.TIMEOUT.code(), message,
                target, operation, DownstreamFailure.TIMEOUT, null, cause);
    }
}