package com.banking.account.exception;


/** The circuit breaker of the target is open, so the call was not even tried. */
public class CircuitOpenException extends DownstreamException {

    public CircuitOpenException(String target, String operation, String message,
                                int retryAfterSeconds, Throwable cause) {
        super(DownstreamFailure.CIRCUIT_OPEN.defaultStatus(), DownstreamFailure.CIRCUIT_OPEN.code(), message,
                target, operation, DownstreamFailure.CIRCUIT_OPEN, retryAfterSeconds, cause);
    }
}