package com.digitalwallet.PaymentsWalletLedger.repository;

import com.digitalwallet.PaymentsWalletLedger.dto.TransferDetailResponse.AuditView;
import com.digitalwallet.PaymentsWalletLedger.dto.TransferDetailResponse.EntryView;
import com.digitalwallet.PaymentsWalletLedger.dto.TransferResponse;
import com.digitalwallet.PaymentsWalletLedger.util.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Plain SQL only. Every write method takes the SAME Connection, so all steps share one transaction. */
@Repository
public class TransferRepository {

    public record LockedAccount(long accountId, Long userId, String accountType, long balancePaise) {}

    private static final String TRANSFER_COLS =
        "transfer_id, transfer_type, status, amount_paise, original_transfer_id, note, created_at";

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ======================= WRITES (inside a transaction) =======================

    public long systemAccountId(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT account_id FROM accounts WHERE system_account_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("System account missing: " + code);
                return rs.getLong(1);
            }
        }
    }

    /** Locks the rows. ORDER BY account_id = same order every time = no deadlocks. */
    public Map<Long, LockedAccount> lockAccounts(Connection c, Collection<Long> ids) throws SQLException {
        String sql = "SELECT account_id, user_id, account_type, balance_paise " +
                     "FROM accounts WHERE account_id = ANY(?) ORDER BY account_id FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
            Map<Long, LockedAccount> result = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong("account_id"), new LockedAccount(
                            rs.getLong("account_id"),
                            rs.getObject("user_id", Long.class),
                            rs.getString("account_type"),
                            rs.getLong("balance_paise")));
                }
            }
            return result;
        }
    }

    public TransferResponse insertTransfer(Connection c, String type, String status, long amount,
                                           String note, Long initiatedBy, Long originalId,
                                           Long fromAccountId, Long toAccountId) throws SQLException {
        String sql = "INSERT INTO transfer (initiated_by_user_id, transfer_type, status, amount_paise, " +
                     "original_transfer_id, note, from_account_id, to_account_id) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + TRANSFER_COLS;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            setNullableLong(ps, 1, initiatedBy);
            ps.setString(2, type);
            ps.setString(3, status);
            ps.setLong(4, amount);
            setNullableLong(ps, 5, originalId);
            ps.setString(6, note);
            setNullableLong(ps, 7, fromAccountId);
            setNullableLong(ps, 8, toAccountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return mapTransfer(rs);
            }
        }
    }

    public record TransferAccounts(Long fromAccountId, Long toAccountId) {}

    /** The 2 accounts a transfer uses (needed to release a HELD transfer that has no entries yet). */
    public TransferAccounts accountsOf(Connection c, long transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT from_account_id, to_account_id FROM transfer WHERE transfer_id = ?")) {
            ps.setLong(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                    ? new TransferAccounts(rs.getObject(1, Long.class), rs.getObject(2, Long.class))
                    : null;
            }
        }
    }

    public void updateStatus(Connection c, long transferId, String newStatus) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE transfer SET status = ? WHERE transfer_id = ?")) {
            ps.setString(1, newStatus);
            ps.setLong(2, transferId);
            ps.executeUpdate();
        }
    }

    public void insertEntry(Connection c, long transferId, long accountId,
                            String direction, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount_paise) " +
                "VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, transferId);
            ps.setLong(2, accountId);
            ps.setString(3, direction);
            ps.setLong(4, amount);
            ps.executeUpdate();
        }
    }

    public void applyDelta(Connection c, long accountId, long delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET balance_paise = balance_paise + ? WHERE account_id = ?")) {
            ps.setLong(1, delta);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        }
    }

    /** changedByType: USER, ADMIN or SYSTEM. SYSTEM must have changedByUserId = null (table rule). */
    public void insertAudit(Connection c, long transferId, String oldStatus, String newStatus,
                            String changedByType, Long changedByUserId, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_logs (transfer_id, old_status, new_status, changed_by_type, " +
                "changed_by_user_id, reason) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, transferId);
            ps.setString(2, oldStatus);
            ps.setString(3, newStatus);
            ps.setString(4, changedByType);
            setNullableLong(ps, 5, changedByUserId);
            ps.setString(6, reason);
            ps.executeUpdate();
        }
    }

    /** Locks the original transfer row so two reversals cannot run at once. */
    public TransferResponse lockTransfer(Connection c, long transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + TRANSFER_COLS + " FROM transfer WHERE transfer_id = ? FOR UPDATE")) {
            ps.setLong(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapTransfer(rs) : null;
            }
        }
    }

    public boolean reversalExists(Connection c, long transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM transfer WHERE original_transfer_id = ?")) {
            ps.setLong(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Same as findEntries but on the transaction's own connection. */
    public List<EntryView> findEntries(Connection c, long transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ledger_entry_id, account_id, direction, amount_paise, created_at " +
                "FROM ledger_entries WHERE transfer_id = ? ORDER BY ledger_entry_id")) {
            ps.setLong(1, transferId);
            List<EntryView> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapEntry(rs));
            }
            return list;
        }
    }

    public record Initiator(Long userId) {}

    /** Owner of a USER_WALLET, or null if the account is missing or is a system account. */
    public Long ownerOfWallet(Connection c, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM accounts WHERE account_id = ? AND account_type = 'USER_WALLET'")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, Long.class) : null;
            }
        }
    }

    /** Who started a transfer. Returns null if the transfer does not exist; userId is null for reversals. */
    public Initiator initiatorOf(Connection c, long transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT initiated_by_user_id FROM transfer WHERE transfer_id = ?")) {
            ps.setLong(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Initiator(rs.getObject(1, Long.class)) : null;
            }
        }
    }

    // ======================= READS (no transaction needed) =======================

    public TransferResponse findTransfer(long transferId) {
        List<TransferResponse> rows = jdbc.query(
            "SELECT " + TRANSFER_COLS + " FROM transfer WHERE transfer_id = ?",
            (rs, i) -> mapTransfer(rs), transferId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<EntryView> findEntries(long transferId) {
        return jdbc.query(
            "SELECT ledger_entry_id, account_id, direction, amount_paise, created_at " +
            "FROM ledger_entries WHERE transfer_id = ? ORDER BY ledger_entry_id",
            (rs, i) -> mapEntry(rs), transferId);
    }

    public List<AuditView> findAudit(long transferId) {
        return jdbc.query(
            "SELECT old_status, new_status, changed_by_type, changed_by_user_id, reason, created_at " +
            "FROM audit_logs WHERE transfer_id = ? ORDER BY audit_id",
            (rs, i) -> new AuditView(
                rs.getString("old_status"),
                rs.getString("new_status"),
                rs.getString("changed_by_type"),
                rs.getObject("changed_by_user_id", Long.class),
                rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class)),
            transferId);
    }

    // ======================= helpers =======================

    private EntryView mapEntry(ResultSet rs) throws SQLException {
        return new EntryView(
                rs.getLong("ledger_entry_id"),
                rs.getLong("account_id"),
                rs.getString("direction"),
                rs.getLong("amount_paise"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private TransferResponse mapTransfer(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_paise");
        return new TransferResponse(
                rs.getLong("transfer_id"),
                rs.getString("transfer_type"),
                rs.getString("status"),
                amount,
                Money.toRupees(amount),
                rs.getObject("original_transfer_id", Long.class),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT);
        else ps.setLong(index, value);
    }
}
