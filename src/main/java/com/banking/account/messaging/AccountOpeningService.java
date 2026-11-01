package com.banking.account.messaging;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.messaging.payload.AccountOpenedPayload;
import com.banking.account.messaging.payload.LedgerAccountCreatedEvent;
import com.banking.account.messaging.payload.LedgerAccountCreationFailedEvent;
import com.banking.account.repository.AccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AccountOpeningService {

    private static final String HANDLER = "cbs-events";

    private final AccountRepository accounts;
    private final ProcessedEventGuard guard;
    private final EventPublisher events;
    private final MeterRegistry meters;

    public AccountOpeningService(AccountRepository accounts,
                                 ProcessedEventGuard guard,
                                 EventPublisher events,
                                 MeterRegistry meters) {
        this.accounts = accounts;
        this.guard = guard;
        this.events = events;
        this.meters = meters;
    }

    @Transactional
    public void ledgerAccountCreated(EventEnvelope event, LedgerAccountCreatedEvent payload) {
        String number = CommandValidation.text(payload.accountNumber(), "accountNumber", 50);

        if (!guard.firstTime(HANDLER, event.eventId())) {
            count("duplicate");
            return;
        }

        Optional<Account> found = accounts.findById(number);
        if (found.isEmpty()) {
            // A ledger account that was not opened through this service (for example a test account).
            count("unknown");
            return;
        }

        Account account = found.get();
        // OPENING_FAILED is included on purpose: a late answer after the timeout still activates the account,
        // so a ledger account never stays without an owner.
        if (account.getStatus() != AccountStatus.PENDING && account.getStatus() != AccountStatus.OPENING_FAILED) {
            count("ignored");
            return;
        }

        account.setStatus(AccountStatus.ACTIVE);
        account.setFailureReason(null);
        events.publish(TopicNames.ACCOUNT_EVENTS, number, "AccountOpened", number, event.eventId(),
                new AccountOpenedPayload(number, account.getUserId(),
                        account.getAccountType().name(), account.getCurrency()));
        count("activated");
    }

    @Transactional
    public void ledgerAccountCreationFailed(EventEnvelope event, LedgerAccountCreationFailedEvent payload) {
        String number = CommandValidation.text(payload.accountNumber(), "accountNumber", 50);

        if (!guard.firstTime(HANDLER, event.eventId())) {
            count("duplicate");
            return;
        }

        Optional<Account> found = accounts.findById(number);
        if (found.isEmpty() || found.get().getStatus() != AccountStatus.PENDING) {
            count("ignored");
            return;
        }

        Account account = found.get();
        account.setStatus(AccountStatus.OPENING_FAILED);
        account.setFailureReason(reasonOf(payload));
        count("failed");
    }

    private String reasonOf(LedgerAccountCreationFailedEvent payload) {
        String reason = payload.reason() == null || payload.reason().isBlank()
                ? "The ledger account could not be created"
                : payload.reason().trim();
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    private void count(String result) {
        meters.counter("account.opening", "result", result).increment();
    }
}