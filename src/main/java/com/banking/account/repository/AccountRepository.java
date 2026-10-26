package com.banking.account.repository;

import com.banking.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByUserIdOrderByCreatedAtAsc(Long userId);

    long countByUserId(Long userId);

    List<Account> findByAccountNumberIn(Collection<String> accountNumbers);
}