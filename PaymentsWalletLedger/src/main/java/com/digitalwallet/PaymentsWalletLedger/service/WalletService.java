package com.digitalwallet.PaymentsWalletLedger.service;


import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.digitalwallet.PaymentsWalletLedger.dto.BalanceResponse;
import com.digitalwallet.PaymentsWalletLedger.model.Account;
import com.digitalwallet.PaymentsWalletLedger.repository.AccountRepository;
import com.digitalwallet.PaymentsWalletLedger.util.Money;

import java.util.List;

@Service
public class WalletService {

    private final AccountRepository accounts;

    public WalletService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account createWallet(long userId, String walletType) {
        if (walletType == null ||
            !(walletType.equals("MAIN") || walletType.equals("SAVING"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "walletType must be MAIN or SAVING");
        }
        if (!accounts.userExists(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        try {
            return accounts.createUserWallet(userId, walletType);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "User already has a " + walletType + " wallet");
        }
    }

    public List<Account> listWallets(long userId) {
        if (!accounts.userExists(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return accounts.findWalletsByUserId(userId);
    }
    
    public BalanceResponse getBalance(long walletId) {
        Account a = accounts.findById(walletId);

        // only user wallets are exposed here, system accounts are hidden
        if (a == null || !"USER_WALLET".equals(a.accountType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found");
        }

        long paise = a.balancePaise();
        String rupees = Money.toRupees(paise);
        return new BalanceResponse(a.accountId(), paise, rupees, a.currency());
    }
}
