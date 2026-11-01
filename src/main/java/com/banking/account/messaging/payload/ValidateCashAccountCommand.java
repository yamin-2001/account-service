package com.banking.account.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidateCashAccountCommand(
        String transferId,
        Long userId,
        String accountNumber,
        String operation
) {}