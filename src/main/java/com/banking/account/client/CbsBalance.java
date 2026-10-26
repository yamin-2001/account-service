package com.banking.account.client;

import java.math.BigDecimal;

public record CbsBalance(String accountNumber, BigDecimal balance, String currency) {}