package com.banking.account.client;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public interface CbsClient {

    void createLedgerAccount(String accountNumber, String currency, BigDecimal openingBalance);

    Map<String, CbsBalance> getBalances(Collection<String> accountNumbers);
}