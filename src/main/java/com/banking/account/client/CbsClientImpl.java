package com.banking.account.client;

import com.banking.account.exception.CbsUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CbsClientImpl.class);

    private final RestClient restClient;

    public CbsClientImpl(@Qualifier("cbsRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Map<String, CbsBalance> getBalances(Collection<String> accountNumbers) {
        if (accountNumbers.isEmpty()) {
            return Map.of();
        }

        try {
            List<CbsBalance> balances = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/cbs/balances")
                            .queryParam("accountNumbers", String.join(",", accountNumbers))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CbsBalance>>() {});

            if (balances == null) {
                return Map.of();
            }
            return balances.stream()
                    .collect(Collectors.toMap(CbsBalance::accountNumber, Function.identity()));
        } catch (RestClientException | IllegalStateException ex) {
            log.error("CBS balance lookup failed: {}", ex.getMessage());
            throw new CbsUnavailableException("Balances are temporarily unavailable, please try again");
        }
    }
}