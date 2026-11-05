package com.banking.account.exception;



public class DownstreamBadResponseException extends DownstreamException {

    public DownstreamBadResponseException(String target, String operation, String message, Throwable cause) {
        super(DownstreamFailure.BAD_RESPONSE.defaultStatus(), DownstreamFailure.BAD_RESPONSE.code(), message,
                target, operation, DownstreamFailure.BAD_RESPONSE, null, cause);
    }
}