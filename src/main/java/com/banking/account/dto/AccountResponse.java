package com.banking.account.dto;

import java.math.BigDecimal;

public record AccountResponse(
        String accountNumber,
        String accountType,
        BigDecimal balance,
        String currency
) {}