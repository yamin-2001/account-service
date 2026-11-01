package com.banking.account.messaging.payload;

public record TransferValidatedPayload(String transferId, String currency, boolean sameOwner) {}