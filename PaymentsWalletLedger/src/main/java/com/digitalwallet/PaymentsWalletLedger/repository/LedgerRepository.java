package com.digitalwallet.PaymentsWalletLedger.repository;

import com.digitalwallet.PaymentsWalletLedger.dto.StatementEntry;
import com.digitalwallet.PaymentsWalletLedger.util.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StatementEntry> findStatement(long accountId, int limit, int offset) {
        String sql =
            "SELECT l.ledger_entry_id, l.transfer_id, t.transfer_type, t.status, " +
            "       l.direction, l.amount_paise, l.created_at, t.note, " +
            "       (SELECT o.account_id FROM ledger_entries o " +
            "         WHERE o.transfer_id = l.transfer_id AND o.account_id <> l.account_id " +
            "         ORDER BY o.ledger_entry_id LIMIT 1) AS counterparty_account_id " +
            "FROM ledger_entries l " +
            "JOIN transfer t ON t.transfer_id = l.transfer_id " +
            "WHERE l.account_id = ? " +
            "ORDER BY l.created_at DESC, l.ledger_entry_id DESC " +
            "LIMIT ? OFFSET ?";

        return jdbc.query(sql, (rs, i) -> new StatementEntry(
                rs.getLong("ledger_entry_id"),
                rs.getLong("transfer_id"),
                rs.getString("transfer_type"),
                rs.getString("status"),
                rs.getString("direction"),
                rs.getLong("amount_paise"),
                Money.toRupees(rs.getLong("amount_paise")),
                rs.getObject("counterparty_account_id", Long.class),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class)),
            accountId, limit, offset);
    }
}