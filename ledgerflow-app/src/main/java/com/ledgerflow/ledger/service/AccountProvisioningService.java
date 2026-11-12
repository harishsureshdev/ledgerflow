package com.ledgerflow.ledger.service;

import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.domain.AccountType;
import com.ledgerflow.ledger.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountProvisioningService {

    private final AccountRepository accountRepository;

    public AccountProvisioningService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account getOrCreateCustomerAccount(String customerId, String currency, long initialBalance) {
        String accountNumber = "CUST-" + customerId + "-" + currency.toUpperCase();
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseGet(() -> {
                    Account account = new Account(accountNumber, AccountType.CUSTOMER, currency, false);
                    if (initialBalance > 0) {
                        account.applyAmount(initialBalance);
                    }
                    return accountRepository.save(account);
                });
    }

    @Transactional
    public Account getOrCreateMerchantAccount(String merchantId, String currency) {
        String accountNumber = "MERCH-" + merchantId + "-" + currency.toUpperCase();
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseGet(() -> accountRepository.save(new Account(accountNumber, AccountType.MERCHANT, currency, true)));
    }

    @Transactional
    public Account getOrCreateSettlementAccount(String currency) {
        String accountNumber = "SETTLEMENT-CLEARING-" + currency.toUpperCase();
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseGet(() -> accountRepository.save(new Account(accountNumber, AccountType.SETTLEMENT, currency, true)));
    }
}
