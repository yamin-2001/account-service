package com.banking.account.controller;

import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.CreateAccountRequest;
import com.banking.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final String DEGRADED_HEADER = "X-Degraded";

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> list(@RequestHeader("X-User-Id") Long userId) {
        List<AccountResponse> accounts = accountService.listForUser(userId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (accounts.stream().anyMatch(account -> !account.balanceAvailable())) {
            response.header(DEGRADED_HEADER, "balances");
        }
        return response.body(accounts);
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> get(@RequestHeader("X-User-Id") Long userId,
                                               @PathVariable String accountNumber) {
        AccountResponse account = accountService.getOne(userId, accountNumber);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (!account.balanceAvailable()) {
            response.header(DEGRADED_HEADER, "balances");
        }
        return response.body(account);
    }

    /** 202 Accepted: the account is created, but CBS opens its ledger account asynchronously. */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@RequestHeader("X-User-Id") Long userId,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accountService.create(userId, request));
    }
}