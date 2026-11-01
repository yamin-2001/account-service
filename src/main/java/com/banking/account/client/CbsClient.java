package com.banking.account.client;

import java.util.Collection;
import java.util.Map;

public interface CbsClient {

    Map<String, CbsBalance> getBalances(Collection<String> accountNumbers);
}