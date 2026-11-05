package com.banking.account.client;

import com.banking.account.resilience.DownstreamErrors;
import com.banking.account.resilience.ResilientCaller;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CbsClientImpl implements CbsClient {

    private static final String TARGET = "cbs";

    private final RestClient restClient;
    private final ResilientCaller resilient;

    public CbsClientImpl(@Qualifier("cbsRestClient") RestClient restClient, ResilientCaller resilient) {
        this.restClient = restClient;
        this.resilient = resilient;
    }

    @Override
    public Map<String, CbsBalance> getBalances(Collection<String> accountNumbers) {
        if (accountNumbers.isEmpty()) {
            return Map.of();
        }
        // A read, so it is safe to try again.
        return resilient.callWithRetry(TARGET, "getBalances", () -> fetch(accountNumbers));
    }

    private Map<String, CbsBalance> fetch(Collection<String> accountNumbers) {
        try {
            List<CbsBalance> balances = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/cbs/balances")
                            .queryParam("accountNumbers", String.join(",", accountNumbers))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CbsBalance>>() {});

            if (balances == null) {
                throw DownstreamErrors.badResponse(TARGET, "getBalances", null);
            }
            return balances.stream()
                    .collect(Collectors.toMap(CbsBalance::accountNumber, Function.identity(), (first, second) -> first));
        } catch (RestClientException | IllegalStateException ex) {
            throw DownstreamErrors.translate(TARGET, "getBalances", ex);
        }
    }
}