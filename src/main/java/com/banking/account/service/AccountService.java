package com.banking.account.service;

import com.banking.account.dto.AccountDetailsResponse;
import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.CreateAccountRequest;

import java.util.List;

public interface AccountService {

    AccountResponse create(Long userId, CreateAccountRequest request);

    List<AccountResponse> listForUser(Long userId);

    AccountDetailsResponse getDetails(String accountNumber);

    List<AccountDetailsResponse> getDetails(List<String> accountNumbers);
}