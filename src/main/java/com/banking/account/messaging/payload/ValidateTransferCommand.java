package com.banking.account.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidateTransferCommand(
        String transferId,
        Long userId,
        String fromAccountNumber,
        String toAccountNumber
) {}