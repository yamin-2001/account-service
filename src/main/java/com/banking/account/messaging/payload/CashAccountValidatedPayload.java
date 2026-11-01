package com.banking.account.messaging.payload;

public record CashAccountValidatedPayload(String transferId, String currency) {}