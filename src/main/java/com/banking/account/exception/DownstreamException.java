package com.banking.account.exception;

import org.springframework.http.HttpStatus;

/** A call to another service failed. Always one of the kinds in DownstreamFailure. */
public abstract class DownstreamException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String target;
    private final String operation;
    private final DownstreamFailure failure;
    private final Integer retryAfterSeconds;

    protected DownstreamException(HttpStatus status, String code, String message, String target,
                                  String operation, DownstreamFailure failure,
                                  Integer retryAfterSeconds, Throwable cause) {
        super(message);
        this.status = status;
        this.code = code;
        this.target = target;
        this.operation = operation;
        this.failure = failure;
        this.retryAfterSeconds = retryAfterSeconds;
        if (cause != null) {
            initCause(cause);
        }
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getTarget() { return target; }
    public String getOperation() { return operation; }
    public DownstreamFailure getFailure() { return failure; }
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
}