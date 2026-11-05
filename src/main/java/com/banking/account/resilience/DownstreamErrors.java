package com.banking.account.resilience;


import com.banking.account.exception.DownstreamBadResponseException;
import com.banking.account.exception.DownstreamException;
import com.banking.account.exception.DownstreamFailure;
import com.banking.account.exception.DownstreamRejectedException;
import com.banking.account.exception.DownstreamTimeoutException;
import com.banking.account.exception.DownstreamUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.UnknownContentTypeException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns whatever went wrong in a call to another service into one of five kinds.
 * The message for the user never contains addresses, ports, or stack text.
 */
public final class DownstreamErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,39}");
    private static final int MAX_MESSAGE_LENGTH = 300;

    private static final Map<String, String> LABELS = Map.of(
            "cbs", "The banking core",
            "otp", "The one-time password service",
            "account", "The account service",
            "charge-fee", "The fee service",
            "notification", "The notification service");

    private DownstreamErrors() {
    }

    /** Use inside a catch block of a client. Anything that is not a failure of the call should not be passed in. */
    public static DownstreamException translate(String target, String operation, Throwable error) {
        if (error instanceof DownstreamException existing) {
            return existing;
        }
        if (error instanceof HttpClientErrorException client) {
            return fromClientError(target, operation, client);
        }
        if (error instanceof HttpServerErrorException server) {
            return fromServerError(target, operation, server);
        }
        if (hasCause(error, SocketTimeoutException.class) || hasCause(error, HttpTimeoutException.class)) {
            return timeout(target, operation, error);
        }
        if (isUnreadableAnswer(error)) {
            return badResponse(target, operation, error);
        }
        // Connection refused, unknown host, no instance in the load balancer, or any other failure of the call.
        return unavailable(target, operation, error);
    }

    public static DownstreamTimeoutException timeout(String target, String operation, Throwable cause) {
        return new DownstreamTimeoutException(target, operation, messageFor(target, DownstreamFailure.TIMEOUT), cause);
    }

    public static DownstreamUnavailableException unavailable(String target, String operation, Throwable cause) {
        return new DownstreamUnavailableException(target, operation,
                messageFor(target, DownstreamFailure.UNAVAILABLE), cause);
    }

    public static DownstreamBadResponseException badResponse(String target, String operation, Throwable cause) {
        return new DownstreamBadResponseException(target, operation,
                messageFor(target, DownstreamFailure.BAD_RESPONSE), cause);
    }

    public static String messageFor(String target, DownstreamFailure failure) {
        String label = LABELS.getOrDefault(target, "The other service");
        return switch (failure) {
            case TIMEOUT -> label + " did not answer in time, please try again";
            case UNAVAILABLE -> label + " is not available right now, please try again";
            case BAD_RESPONSE -> label + " sent an unexpected answer, please try again later";
            case CIRCUIT_OPEN -> label + " is temporarily unavailable, please try again shortly";
            case REJECTED -> label + " refused the request";
        };
    }

    private static DownstreamException fromClientError(String target, String operation, HttpClientErrorException error) {
        int status = error.getStatusCode().value();
        if (status == 408) {
            return timeout(target, operation, error);
        }
        if (status == 429) {
            return unavailable(target, operation, error);
        }

        JsonNode body = readBody(error.getResponseBodyAsString());
        String message = text(body, "message");
        String code = text(body, "code");
        HttpStatus resolved = HttpStatus.resolve(status);

        return new DownstreamRejectedException(
                target,
                operation,
                message != null && !message.isBlank() && message.length() <= MAX_MESSAGE_LENGTH
                        ? message
                        : messageFor(target, DownstreamFailure.REJECTED),
                resolved == null ? HttpStatus.BAD_REQUEST : resolved,
                code != null && CODE.matcher(code).matches() ? code : DownstreamFailure.REJECTED.code(),
                error);
    }

    private static DownstreamException fromServerError(String target, String operation, HttpServerErrorException error) {
        return switch (error.getStatusCode().value()) {
            case 504 -> timeout(target, operation, error);
            case 500, 501, 502 -> badResponse(target, operation, error);
            default -> unavailable(target, operation, error);
        };
    }

    private static boolean isUnreadableAnswer(Throwable error) {
        return error instanceof UnknownContentTypeException
                || hasCause(error, HttpMessageNotReadableException.class)
                || hasCause(error, JsonProcessingException.class);
    }

    private static JsonNode readBody(String text) {
        try {
            return text == null || text.isBlank() ? null : MAPPER.readTree(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}