package com.digitalwallet.PaymentsWalletLedger.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.digitalwallet.PaymentsWalletLedger.model.Account;

import java.util.List;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
        "account_id, user_id, account_type, wallet_type, system_account_code, " +
        "balance_paise, currency, created_at";

    private final RowMapper<Account> mapper = (rs, i) -> new Account(
            rs.getLong("account_id"),
            rs.getObject("user_id", Long.class),
            rs.getString("account_type"),
            rs.getString("wallet_type"),
            rs.getString("system_account_code"),
            rs.getLong("balance_paise"),
            rs.getString("currency")
            );

    public boolean userExists(long userId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    public Account createUserWallet(long userId, String walletType) {
        return jdbc.queryForObject(
            "INSERT INTO accounts (account_type, user_id, wallet_type) " +
            "VALUES ('USER_WALLET', ?, ?) RETURNING " + COLUMNS,
            mapper,
            userId, walletType);
    }

    public List<Account> findWalletsByUserId(long userId) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM accounts " +
            "WHERE user_id = ? AND account_type = 'USER_WALLET' ORDER BY account_id",
            mapper, userId);
    }
    
    public Account findById(long accountId) {
        List<Account> rows = jdbc.query(
            "SELECT " + COLUMNS + " FROM accounts WHERE account_id = ?",
            mapper, accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}