package com.banking.account.exception;


public class DownstreamUnavailableException extends DownstreamException {

    /** Used by the clients of the old synchronous flow. */
    public DownstreamUnavailableException(String message) {
        this("unknown", "unknown", message, null);
    }

    public DownstreamUnavailableException(String target, String operation, String message, Throwable cause) {
        super(DownstreamFailure.UNAVAILABLE.defaultStatus(), DownstreamFailure.UNAVAILABLE.code(), message,
                target, operation, DownstreamFailure.UNAVAILABLE, null, cause);
    }
}