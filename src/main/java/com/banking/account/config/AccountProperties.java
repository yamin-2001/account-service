package com.banking.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app.account")
public record AccountProperties(
        int maxPerUser,
        String defaultCurrency,
        BigDecimal demoOpeningBalance
) {}