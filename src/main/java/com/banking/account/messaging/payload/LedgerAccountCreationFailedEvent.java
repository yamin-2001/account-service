package com.banking.account.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerAccountCreationFailedEvent(String accountNumber, String reasonCode, String reason) {}