package com.banking.account.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record CreateLedgerAccountPayload(
        String accountNumber,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance
) {}