package com.banking.account.messaging.payload;

public record RejectionPayload(String transferId, String reasonCode, String reason) {}