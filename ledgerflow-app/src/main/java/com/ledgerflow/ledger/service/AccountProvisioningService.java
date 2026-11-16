package com.ledgerflow.ledger.service;

import com.ledgerflow.common.domain.Money;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.domain.AccountType;
import com.ledgerflow.ledger.domain.TransactionType;
import com.ledgerflow.ledger.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountProvisioningService {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;

    public AccountProvisioningService(AccountRepository accountRepository, LedgerService ledgerService) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * Opens a customer account. A positive opening balance is NOT written straight into the
     * balance (that would create money from nothing): it is posted as a balanced FUNDING journal
     * transaction from the per-currency EXTERNAL-FUNDING account, which represents money that
     * entered the platform from outside. The unique reference "FUND-<account>" means the account
     * can never be funded twice, even under concurrent opens.
     */
    @Transactional
    public Account getOrCreateCustomerAccount(String customerId, String currency, long initialBalance) {
        String accountNumber = "CUST-" + customerId + "-" + currency.toUpperCase();
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseGet(() -> {
                    Account account = accountRepository.saveAndFlush(
                            new Account(accountNumber, AccountType.CUSTOMER, currency, false));
                    if (initialBalance > 0) {
                        Account funding = getOrCreateExternalFundingAccount(currency);
                        ledgerService.postTransaction(new PostTransactionCommand(
                                "FUND-" + accountNumber,
                                TransactionType.FUNDING,
                                "Opening balance for " + accountNumber,
                                List.of(
                                        new PostTransactionCommand.PostEntryCommand(funding.getId(), new Money(-initialBalance, currency)),
                                        new PostTransactionCommand.PostEntryCommand(account.getId(), new Money(initialBalance, currency))
                                )));
                    }
                    return account;
                });
    }

    /** Read-only lookup, used for the pre-charge balance check (the binding check is the locked one in the ledger). */
    @Transactional(readOnly = true)
    public java.util.Optional<Account> findCustomerAccount(String customerId, String currency) {
        return accountRepository.findByAccountNumber("CUST-" + customerId + "-" + currency.toUpperCase());
    }

    /** Source of all money entering the platform; its (negative) balance is the total brought in. */
    @Transactional
    public Account getOrCreateExternalFundingAccount(String currency) {
        String accountNumber = "EXTERNAL-FUNDING-" + currency.toUpperCase();
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseGet(() -> accountRepository.saveAndFlush(new Account(accountNumber, AccountType.CLEARING, currency, true)));
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
