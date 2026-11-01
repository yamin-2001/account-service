package com.banking.account.repository;

import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByUserIdAndStatusNotOrderByCreatedAtAsc(Long userId, AccountStatus status);

    long countByUserIdAndStatusNot(Long userId, AccountStatus status);

    long countByStatus(AccountStatus status);

    Optional<Account> findByAccountNumberAndUserId(String accountNumber, Long userId);

    List<Account> findByAccountNumberIn(Collection<String> accountNumbers);

    /** One atomic statement, so two running instances cannot both handle the same account. */
    @Modifying
    @Query("update Account a set a.status = :to, a.failureReason = :reason "
            + "where a.status = :from and a.createdAt < :cutoff")
    int failStalePending(@Param("from") AccountStatus from,
                         @Param("to") AccountStatus to,
                         @Param("reason") String reason,
                         @Param("cutoff") LocalDateTime cutoff);
}