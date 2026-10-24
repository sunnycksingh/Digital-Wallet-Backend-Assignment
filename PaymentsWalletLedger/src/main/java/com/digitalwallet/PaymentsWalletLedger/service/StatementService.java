package com.digitalwallet.PaymentsWalletLedger.service;

import com.digitalwallet.PaymentsWalletLedger.dto.StatementEntry;
import com.digitalwallet.PaymentsWalletLedger.dto.StatementResponse;
import com.digitalwallet.PaymentsWalletLedger.model.Account;
import com.digitalwallet.PaymentsWalletLedger.repository.AccountRepository;
import com.digitalwallet.PaymentsWalletLedger.repository.LedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class StatementService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public StatementService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public StatementResponse getStatement(long walletId, Integer limitParam, Integer offsetParam) {
        int limit = (limitParam == null) ? DEFAULT_LIMIT : limitParam;
        int offset = (offsetParam == null) ? 0 : offsetParam;

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "offset must be 0 or more");
        }

        Account a = accounts.findById(walletId);
        if (a == null || !"USER_WALLET".equals(a.accountType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found");
        }

        List<StatementEntry> rows = ledger.findStatement(walletId, limit, offset);
        return new StatementResponse(walletId, limit, offset, rows.size(), rows);
    }
}