package com.banking.account.service.impl;

import com.banking.account.client.CbsBalance;
import com.banking.account.client.CbsClient;
import com.banking.account.config.AccountProperties;
import com.banking.account.dto.AccountDetailsResponse;
import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.CreateAccountRequest;
import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.exception.AccountCreationFailedException;
import com.banking.account.exception.AccountNotFoundException;
import com.banking.account.exception.DownstreamException;
import com.banking.account.exception.MaxAccountsReachedException;
import com.banking.account.messaging.EventPublisher;
import com.banking.account.messaging.TopicNames;
import com.banking.account.messaging.payload.CreateLedgerAccountPayload;
import com.banking.account.repository.AccountRepository;
import com.banking.account.service.AccountService;
import com.banking.account.util.AccountNumberGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class AccountServiceImpl implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);
    private static final int MAX_NUMBER_ATTEMPTS = 5;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final AccountRepository accountRepository;
    private final CbsClient cbsClient;
    private final AccountNumberGenerator numberGenerator;
    private final AccountProperties properties;
    private final EventPublisher events;
    private final MeterRegistry meters;

    public AccountServiceImpl(AccountRepository accountRepository,
                              CbsClient cbsClient,
                              AccountNumberGenerator numberGenerator,
                              AccountProperties properties,
                              EventPublisher events,
                              MeterRegistry meters) {
        this.accountRepository = accountRepository;
        this.cbsClient = cbsClient;
        this.numberGenerator = numberGenerator;
        this.properties = properties;
        this.events = events;
        this.meters = meters;
    }

    @Override
    @Transactional
    public AccountResponse create(Long userId, CreateAccountRequest request) {
        if (accountRepository.countByUserIdAndStatusNot(userId, AccountStatus.OPENING_FAILED)
                >= properties.maxPerUser()) {
            throw new MaxAccountsReachedException(
                    "You can have at most " + properties.maxPerUser() + " accounts");
        }

        String currency = properties.defaultCurrency();
        BigDecimal opening = properties.demoOpeningBalance() == null
                ? ZERO
                : properties.demoOpeningBalance().setScale(2);

        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String number = numberGenerator.next();
            if (accountRepository.existsById(number)) {
                continue;
            }

            Account account = new Account();
            account.setAccountNumber(number);
            account.setUserId(userId);
            account.setAccountType(request.accountType());
            account.setCurrency(currency);
            account.setStatus(AccountStatus.PENDING);
            accountRepository.save(account);

            // Saved in the same transaction. CBS creates the ledger account and answers with an event.
            events.publish(TopicNames.CBS_COMMANDS, number, "CreateLedgerAccount", number, null,
                    new CreateLedgerAccountPayload(number, currency, opening));

            return new AccountResponse(number, request.accountType().name(), ZERO, currency,
                    AccountStatus.PENDING.name(), null, true);
        }

        throw new AccountCreationFailedException("Could not create the account, please try again");
    }

    @Override
    public List<AccountResponse> listForUser(Long userId) {
        List<Account> accounts = accountRepository
                .findByUserIdAndStatusNotOrderByCreatedAtAsc(userId, AccountStatus.OPENING_FAILED);
        if (accounts.isEmpty()) {
            return List.of();
        }

        // Only accounts that already exist in the ledger have a balance there.
        List<String> ledgerNumbers = accounts.stream()
                .filter(this::hasLedger)
                .map(Account::getAccountNumber)
                .toList();
        Map<String, CbsBalance> balances = fetchBalances(ledgerNumbers);

        return accounts.stream().map(account -> toResponse(account, balances)).toList();
    }

    @Override
    public AccountResponse getOne(Long userId, String accountNumber) {
        Account account = accountRepository.findByAccountNumberAndUserId(accountNumber.trim(), userId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));

        Map<String, CbsBalance> balances = hasLedger(account)
                ? fetchBalances(List.of(account.getAccountNumber()))
                : Map.of();

        return toResponse(account, balances);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDetailsResponse getDetails(String accountNumber) {
        return accountRepository.findById(accountNumber.trim())
                .map(this::toDetails)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountDetailsResponse> getDetails(List<String> accountNumbers) {
        if (accountNumbers == null || accountNumbers.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = accountNumbers.stream().map(String::trim).toList();
        return accountRepository.findByAccountNumberIn(cleaned).stream()
                .map(this::toDetails)
                .toList();
    }

    /**
     * The balances are extra information. When the ledger cannot be reached the accounts are still shown,
     * each marked "balance unavailable". Only failures of the call are handled here: a real bug still fails.
     */
    private Map<String, CbsBalance> fetchBalances(List<String> numbers) {
        if (numbers.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, CbsBalance> balances = cbsClient.getBalances(numbers);
            if (balances.size() < numbers.size()) {
                log.error("The ledger has no balance for {} of {} accounts", numbers.size() - balances.size(), numbers.size());
                meters.counter("accounts.balances.degraded", "reason", "MISSING").increment();
            }
            return balances;
        } catch (DownstreamException ex) {
            log.warn("Balances are unavailable ({}), showing the accounts without them", ex.getFailure());
            meters.counter("accounts.balances.degraded", "reason", ex.getFailure().name()).increment();
            return Map.of();
        }
    }

    private boolean hasLedger(Account account) {
        return account.getStatus() == AccountStatus.ACTIVE || account.getStatus() == AccountStatus.BLOCKED;
    }

    private AccountResponse toResponse(Account account, Map<String, CbsBalance> balances) {
        BigDecimal balance = ZERO;
        boolean available = true;
        if (hasLedger(account)) {
            CbsBalance ledger = balances.get(account.getAccountNumber());
            balance = ledger == null ? null : ledger.balance();
            available = ledger != null;
        }
        return new AccountResponse(
                account.getAccountNumber(),
                account.getAccountType().name(),
                balance,
                account.getCurrency(),
                account.getStatus().name(),
                account.getFailureReason(),
                available);
    }

    private AccountDetailsResponse toDetails(Account account) {
        return new AccountDetailsResponse(
                account.getAccountNumber(),
                account.getUserId(),
                account.getAccountType().name(),
                account.getCurrency(),
                account.getStatus().name());
    }
}