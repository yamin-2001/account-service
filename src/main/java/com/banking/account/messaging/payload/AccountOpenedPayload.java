package com.banking.account.messaging.payload;

public record AccountOpenedPayload(String accountNumber, Long userId, String accountType, String currency) {}