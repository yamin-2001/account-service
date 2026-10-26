package com.banking.account.dto;

public record AccountDetailsResponse(
        String accountNumber,
        Long userId,
        String accountType,
        String currency,
        String status
) {}