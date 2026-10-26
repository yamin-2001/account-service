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
import com.banking.account.exception.LedgerAccountConflictException;
import com.banking.account.exception.MaxAccountsReachedException;
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

    private final AccountRepository accountRepository;
    private final CbsClient cbsClient;
    private final AccountNumberGenerator numberGenerator;
    private final AccountProperties properties;

    public AccountServiceImpl(AccountRepository accountRepository,
                              CbsClient cbsClient,
                              AccountNumberGenerator numberGenerator,
                              AccountProperties properties) {
        this.accountRepository = accountRepository;
        this.cbsClient = cbsClient;
        this.numberGenerator = numberGenerator;
        this.properties = properties;
    }

    @Override
    public AccountResponse create(Long userId, CreateAccountRequest request) {
        if (accountRepository.countByUserId(userId) >= properties.maxPerUser()) {
            throw new MaxAccountsReachedException(
                    "You can have at most " + properties.maxPerUser() + " accounts");
        }

        String currency = properties.defaultCurrency();
        BigDecimal opening = properties.demoOpeningBalance() == null
                ? BigDecimal.ZERO.setScale(2)
                : properties.demoOpeningBalance().setScale(2);

        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String number = numberGenerator.next();
            if (accountRepository.existsById(number)) {
                continue;
            }

            // CBS first. If this fails, nothing is saved here, so there is no account without a ledger.
            try {
                cbsClient.createLedgerAccount(number, currency, opening);
            } catch (LedgerAccountConflictException ex) {
                continue;
            }

            Account account = new Account();
            account.setAccountNumber(number);
            account.setUserId(userId);
            account.setAccountType(request.accountType());
            account.setCurrency(currency);
            account.setStatus(AccountStatus.ACTIVE);
            accountRepository.save(account);

            return new AccountResponse(number, request.accountType().name(), opening, currency);
        }

        throw new AccountCreationFailedException("Could not create the account, please try again");
    }

    @Override
    public List<AccountResponse> listForUser(Long userId) {
        List<Account> accounts = accountRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (accounts.isEmpty()) {
            return List.of();
        }

        Map<String, CbsBalance> balances = cbsClient.getBalances(
                accounts.stream().map(Account::getAccountNumber).toList());

        return accounts.stream().map(account -> {
            CbsBalance balance = balances.get(account.getAccountNumber());
            if (balance == null) {
                log.error("No ledger balance for account {}", account.getAccountNumber());
                throw new CbsUnavailableException("Balance temporarily unavailable, please try again");
            }
            return new AccountResponse(
                    account.getAccountNumber(),
                    account.getAccountType().name(),
                    balance.balance(),
                    account.getCurrency());
        }).toList();
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

    private AccountDetailsResponse toDetails(Account account) {
        return new AccountDetailsResponse(
                account.getAccountNumber(),
                account.getUserId(),
                account.getAccountType().name(),
                account.getCurrency(),
                account.getStatus().name());
    }
}