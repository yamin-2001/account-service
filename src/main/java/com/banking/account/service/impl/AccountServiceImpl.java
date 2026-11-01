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
import com.banking.account.exception.CbsUnavailableException;
import com.banking.account.exception.MaxAccountsReachedException;
import com.banking.account.messaging.EventPublisher;
import com.banking.account.messaging.TopicNames;
import com.banking.account.messaging.payload.CreateLedgerAccountPayload;
import com.banking.account.repository.AccountRepository;
import com.banking.account.service.AccountService;
import com.banking.account.util.AccountNumberGenerator;
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

    public AccountServiceImpl(AccountRepository accountRepository,
                              CbsClient cbsClient,
                              AccountNumberGenerator numberGenerator,
                              AccountProperties properties,
                              EventPublisher events) {
        this.accountRepository = accountRepository;
        this.cbsClient = cbsClient;
        this.numberGenerator = numberGenerator;
        this.properties = properties;
        this.events = events;
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
                    AccountStatus.PENDING.name(), null);
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
                .filter(account -> account.getStatus() != AccountStatus.PENDING)
                .map(Account::getAccountNumber)
                .toList();
        Map<String, CbsBalance> balances = cbsClient.getBalances(ledgerNumbers);

        return accounts.stream().map(account -> toResponse(account, balances)).toList();
    }

    @Override
    public AccountResponse getOne(Long userId, String accountNumber) {
        Account account = accountRepository.findByAccountNumberAndUserId(accountNumber.trim(), userId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));

        boolean hasLedger = account.getStatus() == AccountStatus.ACTIVE
                || account.getStatus() == AccountStatus.BLOCKED;
        Map<String, CbsBalance> balances = hasLedger
                ? cbsClient.getBalances(List.of(account.getAccountNumber()))
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

    private AccountResponse toResponse(Account account, Map<String, CbsBalance> balances) {
        BigDecimal balance = ZERO;
        if (account.getStatus() == AccountStatus.ACTIVE || account.getStatus() == AccountStatus.BLOCKED) {
            CbsBalance ledger = balances.get(account.getAccountNumber());
            if (ledger == null) {
                log.error("No ledger balance for account {}", account.getAccountNumber());
                throw new CbsUnavailableException("Balance temporarily unavailable, please try again");
            }
            balance = ledger.balance();
        }
        return new AccountResponse(
                account.getAccountNumber(),
                account.getAccountType().name(),
                balance,
                account.getCurrency(),
                account.getStatus().name(),
                account.getFailureReason());
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