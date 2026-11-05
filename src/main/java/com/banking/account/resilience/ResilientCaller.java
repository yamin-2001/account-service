package com.banking.account.resilience;

import com.banking.account.exception.CircuitOpenException;
import com.banking.account.exception.DownstreamFailure;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Runs a call to another service behind the circuit breaker named after the target, and optionally with
 * retries. The settings live in application.yml (resilience4j.circuitbreaker.instances.<target> and
 * resilience4j.retry.instances.<target>-read). The call itself must throw DownstreamException types.
 */
@Component
public class ResilientCaller {

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;

    public ResilientCaller(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
    }

    /** One attempt, behind the circuit breaker. For anything that changes data or uses up something. */
    public <T> T call(String target, String operation, Supplier<T> call) {
        return execute(target, operation, call, false);
    }

    /** Several attempts for temporary failures, each behind the circuit breaker. For reads only. */
    public <T> T callWithRetry(String target, String operation, Supplier<T> call) {
        return execute(target, operation, call, true);
    }

    private <T> T execute(String target, String operation, Supplier<T> call, boolean retry) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(target);
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, call);
        if (retry) {
            guarded = Retry.decorateSupplier(retries.retry(target + "-read"), guarded);
        }
        try {
            return guarded.get();
        } catch (CallNotPermittedException ex) {
            throw new CircuitOpenException(target, operation,
                    DownstreamErrors.messageFor(target, DownstreamFailure.CIRCUIT_OPEN),
                    retryAfterSeconds(breaker), ex);
        }
    }

    private int retryAfterSeconds(CircuitBreaker breaker) {
        long millis = breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
        return (int) Math.max(1, Math.ceil(millis / 1000.0));
    }
}