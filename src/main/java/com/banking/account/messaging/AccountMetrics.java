package com.banking.account.messaging;

import com.banking.account.entity.AccountStatus;
import com.banking.account.repository.AccountRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AccountMetrics {

    public AccountMetrics(MeterRegistry registry, AccountRepository accounts) {
        Gauge.builder("accounts.pending", () -> accounts.countByStatus(AccountStatus.PENDING))
                .description("Accounts waiting for CBS to create their ledger account")
                .register(registry);
    }
}