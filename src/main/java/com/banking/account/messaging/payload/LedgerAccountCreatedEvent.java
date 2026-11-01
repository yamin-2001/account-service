package com.banking.account.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerAccountCreatedEvent(String accountNumber, String currency) {}