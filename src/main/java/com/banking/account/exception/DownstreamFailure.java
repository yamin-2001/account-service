package com.banking.account.exception;


import org.springframework.http.HttpStatus;

/** The kinds of failure when this service calls another service. */
public enum DownstreamFailure {
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "DOWNSTREAM_TIMEOUT"),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE"),
    BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_BAD_RESPONSE"),
    CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "CIRCUIT_OPEN"),
    /** The other service understood the request and refused it. Its own status and code are used. */
    REJECTED(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_REJECTED");

    private final HttpStatus defaultStatus;
    private final String code;

    DownstreamFailure(HttpStatus defaultStatus, String code) {
        this.defaultStatus = defaultStatus;
        this.code = code;
    }

    public HttpStatus defaultStatus() {
        return defaultStatus;
    }

    public String code() {
        return code;
    }
}