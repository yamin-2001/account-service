package com.banking.account.messaging;

import com.banking.account.entity.AccountStatus;
import com.banking.account.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class PendingAccountSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingAccountSweeper.class);

    private final AccountRepository accounts;
    private final int timeoutMinutes;

    public PendingAccountSweeper(AccountRepository accounts,
                                 @Value("${app.account.pending-timeout-minutes:10}") int timeoutMinutes) {
        this.accounts = accounts;
        this.timeoutMinutes = timeoutMinutes;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void failStalePendingAccounts() {
        int failed = accounts.failStalePending(
                AccountStatus.PENDING,
                AccountStatus.OPENING_FAILED,
                "Opening timed out. Please try again",
                LocalDateTime.now().minusMinutes(timeoutMinutes));
        if (failed > 0) {
            log.warn("{} account(s) waited more than {} minutes for the ledger and were marked OPENING_FAILED",
                    failed, timeoutMinutes);
        }
    }
}