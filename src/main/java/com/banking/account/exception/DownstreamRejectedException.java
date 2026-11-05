package com.banking.account.exception;



import org.springframework.http.HttpStatus;

/** The other service refused the request. Carries its status and its code. */
public class DownstreamRejectedException extends DownstreamException {

    public DownstreamRejectedException(String target, String operation, String message,
                                       HttpStatus status, String code, Throwable cause) {
        super(status, code, message, target, operation, DownstreamFailure.REJECTED, null, cause);
    }
}