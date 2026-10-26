package com.banking.account.controller;

import com.banking.account.dto.AccountDetailsResponse;
import com.banking.account.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private final AccountService accountService;

    public InternalAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountDetailsResponse> get(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.getDetails(accountNumber));
    }

    @GetMapping
    public ResponseEntity<List<AccountDetailsResponse>> getMany(@RequestParam List<String> accountNumbers) {
        return ResponseEntity.ok(accountService.getDetails(accountNumbers));
    }
}