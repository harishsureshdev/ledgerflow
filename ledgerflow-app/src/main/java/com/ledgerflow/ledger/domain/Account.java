package com.ledgerflow.ledger.domain;

import com.ledgerflow.common.domain.Money;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false)
    private Long balance = 0L;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    protected Account() {
    }

    public Account(String accountNumber, AccountType accountType, String currency, boolean allowNegative) {
        this.accountNumber = Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        this.accountType = Objects.requireNonNull(accountType, "accountType must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null").toUpperCase();
        this.allowNegative = allowNegative;
        this.balance = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getBalance() {
        return balance;
    }

    public Money getBalanceMoney() {
        return Money.of(balance, currency);
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void applyAmount(long deltaMinorUnits) {
        long newBalance = Math.addExact(this.balance, deltaMinorUnits);
        if (!allowNegative && newBalance < 0) {
            throw new IllegalStateException(String.format(
                    "Account %s (%s) has insufficient funds: current balance=%d, delta=%d, resulting balance=%d",
                    accountNumber, id, balance, deltaMinorUnits, newBalance));
        }
        this.balance = newBalance;
        this.updatedAt = Instant.now();
    }
}
