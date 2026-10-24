package com.digitalwallet.PaymentsWalletLedger.repository;

import com.digitalwallet.PaymentsWalletLedger.dto.RiskDtos.RiskQueueItem;
import com.digitalwallet.PaymentsWalletLedger.util.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Plain SQL for fraud rules and risk flags. */
@Repository
public class RiskRepository {

    public record Rule(long ruleId, String ruleName, String ruleType, Long threshold,
                       int windowMinutes, String action) {}

    private final JdbcTemplate jdbc;

    public RiskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ======================= inside the transfer transaction =======================

    public List<Rule> loadActiveRules(Connection c) throws SQLException {
        String sql = "SELECT rule_id, rule_name, rule_type, threshold_value, " +
                     "COALESCE((configuration->>'windowMinutes')::int, 10) AS window_minutes, action " +
                     "FROM fraud_rules WHERE is_active ORDER BY rule_id";
        List<Rule> rules = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rules.add(new Rule(rs.getLong("rule_id"), rs.getString("rule_name"),
                        rs.getString("rule_type"), rs.getObject("threshold_value", Long.class),
                        rs.getInt("window_minutes"), rs.getString("action")));
            }
        }
        return rules;
    }

    /** VELOCITY rule query. Uses the index on ledger_entries (account_id, created_at DESC, ...). */
    public long countRecentDebits(Connection c, long accountId, int windowMinutes) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ledger_entries " +
                     "WHERE account_id = ? AND direction = 'DEBIT' " +
                     "AND created_at > now() - (? * interval '1 minute')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setInt(2, windowMinutes);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void insertFlag(Connection c, long transferId, long ruleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO risk_flags (transfer_id, rule_id) VALUES (?, ?) " +
                "ON CONFLICT (transfer_id, rule_id) DO NOTHING")) {
            ps.setLong(1, transferId);
            ps.setLong(2, ruleId);
            ps.executeUpdate();
        }
    }

    /** Closes every OPEN flag of a transfer. Returns how many flags were changed. */
    public int closeOpenFlags(Connection c, long transferId, String newStatus) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE risk_flags SET status = ? WHERE transfer_id = ? AND status = 'OPEN'")) {
            ps.setString(1, newStatus);
            ps.setLong(2, transferId);
            return ps.executeUpdate();
        }
    }

    public boolean userExists(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ======================= admin queue (read only) =======================

    public List<RiskQueueItem> findQueue(int limit, int offset) {
        String sql =
            "SELECT f.risk_flag_id, f.transfer_id, f.status AS flag_status, f.llm_label, f.llm_explanation, " +
            "       r.rule_name, r.action, " +
            "       t.transfer_type, t.status AS transfer_status, t.amount_paise, " +
            "       t.initiated_by_user_id, t.created_at " +
            "FROM risk_flags f " +
            "JOIN fraud_rules r ON r.rule_id = f.rule_id " +
            "JOIN transfer t ON t.transfer_id = f.transfer_id " +
            "WHERE f.status = 'OPEN' " +
            "ORDER BY f.risk_flag_id DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, i) -> new RiskQueueItem(
                rs.getLong("risk_flag_id"),
                rs.getLong("transfer_id"),
                rs.getString("transfer_type"),
                rs.getString("transfer_status"),
                rs.getLong("amount_paise"),
                Money.toRupees(rs.getLong("amount_paise")),
                rs.getObject("initiated_by_user_id", Long.class),
                rs.getString("rule_name"),
                rs.getString("action"),
                rs.getString("flag_status"),
                rs.getString("llm_label"),
                rs.getString("llm_explanation"),
                rs.getObject("created_at", OffsetDateTime.class)),
            limit, offset);
    }
}
