package com.banking.account.messaging;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.messaging.payload.CashAccountValidatedPayload;
import com.banking.account.messaging.payload.RejectionPayload;
import com.banking.account.messaging.payload.TransferValidatedPayload;
import com.banking.account.messaging.payload.ValidateCashAccountCommand;
import com.banking.account.messaging.payload.ValidateTransferCommand;
import com.banking.account.repository.AccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountCommandService {

    private static final String HANDLER = "account-commands";

    /** Why a request was refused. */
    private record Rejection(String code, String reason) {}

    private final AccountRepository accounts;
    private final ProcessedEventGuard guard;
    private final EventPublisher events;
    private final MeterRegistry meters;

    public AccountCommandService(AccountRepository accounts,
                                 ProcessedEventGuard guard,
                                 EventPublisher events,
                                 MeterRegistry meters) {
        this.accounts = accounts;
        this.guard = guard;
        this.events = events;
        this.meters = meters;
    }

    @Transactional
    public void validateTransfer(EventEnvelope command, ValidateTransferCommand cmd) {
        String transferId = CommandValidation.text(cmd.transferId(), "transferId", 80);
        Long userId = CommandValidation.required(cmd.userId(), "userId");
        String from = CommandValidation.text(cmd.fromAccountNumber(), "fromAccountNumber", 50);
        String to = CommandValidation.text(cmd.toAccountNumber(), "toAccountNumber", 50);

        if (!guard.firstTime(HANDLER, command.eventId())) {
            count("ValidateTransfer", "duplicate");
            return;
        }

        Account fromAccount = null;
        Account toAccount = null;
        Rejection rejection;
        if (from.equals(to)) {
            rejection = new Rejection("SAME_ACCOUNT", "Source and destination are the same account");
        } else {
            fromAccount = accounts.findById(from).orElse(null);
            toAccount = accounts.findById(to).orElse(null);
            rejection = checkTransfer(userId, from, to, fromAccount, toAccount);
        }

        if (rejection == null) {
            boolean sameOwner = userId.equals(toAccount.getUserId());
            events.publish(TopicNames.ACCOUNT_EVENTS, transferId, "TransferValidated", transferId,
                    command.eventId(),
                    new TransferValidatedPayload(transferId, fromAccount.getCurrency(), sameOwner));
            count("ValidateTransfer", "validated");
        } else {
            events.publish(TopicNames.ACCOUNT_EVENTS, transferId, "TransferRejected", transferId,
                    command.eventId(),
                    new RejectionPayload(transferId, rejection.code(), rejection.reason()));
            count("ValidateTransfer", "rejected");
        }
    }

    @Transactional
    public void validateCashAccount(EventEnvelope command, ValidateCashAccountCommand cmd) {
        String transferId = CommandValidation.text(cmd.transferId(), "transferId", 80);
        Long userId = CommandValidation.required(cmd.userId(), "userId");
        String number = CommandValidation.text(cmd.accountNumber(), "accountNumber", 50);
        CommandValidation.oneOf(cmd.operation(), "operation", "DEPOSIT", "WITHDRAWAL");

        if (!guard.firstTime(HANDLER, command.eventId())) {
            count("ValidateCashAccount", "duplicate");
            return;
        }

        Account account = accounts.findById(number).orElse(null);
        Rejection rejection = null;
        if (account == null) {
            rejection = new Rejection("ACCOUNT_NOT_FOUND", "Account not found: " + number);
        } else if (!userId.equals(account.getUserId())) {
            rejection = new Rejection("NOT_OWNER", "You do not own this account");
        } else if (account.getStatus() != AccountStatus.ACTIVE) {
            rejection = new Rejection("ACCOUNT_INACTIVE", "Account is not active");
        }

        if (rejection == null) {
            events.publish(TopicNames.ACCOUNT_EVENTS, transferId, "CashAccountValidated", transferId,
                    command.eventId(), new CashAccountValidatedPayload(transferId, account.getCurrency()));
            count("ValidateCashAccount", "validated");
        } else {
            events.publish(TopicNames.ACCOUNT_EVENTS, transferId, "CashAccountRejected", transferId,
                    command.eventId(),
                    new RejectionPayload(transferId, rejection.code(), rejection.reason()));
            count("ValidateCashAccount", "rejected");
        }
    }

    private Rejection checkTransfer(Long userId, String from, String to, Account fromAccount, Account toAccount) {
        if (fromAccount == null) {
            return new Rejection("ACCOUNT_NOT_FOUND", "Account not found: " + from);
        }
        if (toAccount == null) {
            return new Rejection("ACCOUNT_NOT_FOUND", "Account not found: " + to);
        }
        if (!userId.equals(fromAccount.getUserId())) {
            return new Rejection("NOT_OWNER", "You do not own the source account");
        }
        if (fromAccount.getStatus() != AccountStatus.ACTIVE || toAccount.getStatus() != AccountStatus.ACTIVE) {
            return new Rejection("ACCOUNT_INACTIVE", "Account is not active");
        }
        if (!fromAccount.getCurrency().equals(toAccount.getCurrency())) {
            return new Rejection("CURRENCY_MISMATCH", "Currency mismatch between accounts");
        }
        return null;
    }

    private void count(String command, String result) {
        meters.counter("account.commands", "command", command, "result", result).increment();
    }
}