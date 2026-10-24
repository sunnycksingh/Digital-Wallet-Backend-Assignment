package com.digitalwallet.PaymentsWalletLedger.repository;

import com.digitalwallet.PaymentsWalletLedger.dto.ReconciliationDtos.IssueView;
import com.digitalwallet.PaymentsWalletLedger.dto.ReconciliationDtos.RunView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** All SQL for the nightly books check. The 5 checks are plain read-only queries. */
@Repository
public class ReconciliationRepository {

    public record Issue(String checkType, Long transferId, Long accountId, Long ledgerEntryId,
                        Long expected, Long actual, String description) {}

    private static final String DEBITS  = "COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'DEBIT'), 0)";
    private static final String CREDITS = "COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'CREDIT'), 0)";

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ======================= lock and run bookkeeping =======================

    public void ensureLockRow() {
        jdbc.update("INSERT INTO job_locks (job_name) VALUES ('reconciliation') " +
                    "ON CONFLICT (job_name) DO NOTHING");
    }

    /** SELECT ... FOR UPDATE NOWAIT. Returns false if another run already holds the row. */
    public boolean tryLock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT job_lock_id FROM job_locks WHERE job_name = 'reconciliation' FOR UPDATE NOWAIT");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            if ("55P03".equals(e.getSQLState())) return false;   // lock_not_available
            throw e;
        }
    }

    public long insertRun(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO reconciliation_runs DEFAULT VALUES RETURNING report_id");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public void completeRun(Connection c, long runId, long problemCount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE reconciliation_runs SET status = 'COMPLETED', ended_at = clock_timestamp(), " +
                "problem_count = ? WHERE report_id = ?")) {
            ps.setLong(1, problemCount);
            ps.setLong(2, runId);
            ps.executeUpdate();
        }
    }

    /** Used when a run crashes. Its own little transaction, because the main one was rolled back. */
    public void recordFailedRun() {
        jdbc.update("INSERT INTO reconciliation_runs (status, ended_at) VALUES ('FAILED', clock_timestamp())");
    }

    public void insertIssue(Connection c, long runId, Issue i) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO reconciliation_issues (report_id, check_type, transfer_id, account_id, " +
                "ledger_entry_id, expected_value, actual_value, description) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, runId);
            ps.setString(2, i.checkType());
            setLong(ps, 3, i.transferId());
            setLong(ps, 4, i.accountId());
            setLong(ps, 5, i.ledgerEntryId());
            setLong(ps, 6, i.expected());
            setLong(ps, 7, i.actual());
            ps.setString(8, i.description());
            ps.executeUpdate();
        }
    }

    // ======================= THE 5 CHECKS (read only) =======================

    /** Check 1: total debits must equal total credits. */
    public List<Issue> checkGlobalBalance(Connection c) throws SQLException {
        String sql = "SELECT " + DEBITS + ", " + CREDITS + " FROM ledger_entries";
        List<Issue> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            long debits = rs.getLong(1), credits = rs.getLong(2);
            if (debits != credits) {
                out.add(new Issue("GLOBAL_BALANCE", null, null, null, debits, credits,
                        "Total debits " + debits + " do not equal total credits " + credits));
            }
        }
        return out;
    }

    /** Check 2: every transfer's debits must equal its credits. */
    public List<Issue> checkTransferBalance(Connection c) throws SQLException {
        String sql = "SELECT transfer_id, " + DEBITS + " AS debits, " + CREDITS + " AS credits " +
                     "FROM ledger_entries GROUP BY transfer_id " +
                     "HAVING " + DEBITS + " <> " + CREDITS + " ORDER BY transfer_id";
        List<Issue> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("transfer_id"), debits = rs.getLong("debits"), credits = rs.getLong("credits");
                out.add(new Issue("TRANSFER_BALANCE", id, null, null, debits, credits,
                        "Transfer " + id + " has debits " + debits + " but credits " + credits));
            }
        }
        return out;
    }

    /** Check 3: stored balance must equal the balance computed from the entries (credits - debits). */
    public List<Issue> checkStoredBalance(Connection c) throws SQLException {
        String computed = "COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_paise " +
                          "ELSE -l.amount_paise END), 0)";
        String sql = "SELECT a.account_id, a.balance_paise AS stored, " + computed + " AS computed " +
                     "FROM accounts a LEFT JOIN ledger_entries l ON l.account_id = a.account_id " +
                     "GROUP BY a.account_id, a.balance_paise " +
                     "HAVING a.balance_paise <> " + computed + " ORDER BY a.account_id";
        List<Issue> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("account_id"), stored = rs.getLong("stored"), comp = rs.getLong("computed");
                out.add(new Issue("STORED_BALANCE", null, id, null, comp, stored,
                        "Account " + id + " stores " + stored + " but its entries add up to " + comp));
            }
        }
        return out;
    }

    /** Check 4: no user wallet may have a computed balance below zero. */
    public List<Issue> checkOverdrafts(Connection c) throws SQLException {
        String computed = "COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_paise " +
                          "ELSE -l.amount_paise END), 0)";
        String sql = "SELECT a.account_id, " + computed + " AS computed " +
                     "FROM accounts a LEFT JOIN ledger_entries l ON l.account_id = a.account_id " +
                     "WHERE a.account_type = 'USER_WALLET' " +
                     "GROUP BY a.account_id HAVING " + computed + " < 0 ORDER BY a.account_id";
        List<Issue> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("account_id"), comp = rs.getLong("computed");
                out.add(new Issue("OVERDRAFT", null, id, null, 0L, comp,
                        "Wallet " + id + " computed balance is negative: " + comp));
            }
        }
        return out;
    }

    /** Check 5a: a COMPLETED transfer with no entries (HELD or PENDING ones may have none). */
    public List<Issue> checkTransfersWithoutEntries(Connection c) throws SQLException {
        String sql = "SELECT t.transfer_id FROM transfer t " +
                     "LEFT JOIN ledger_entries l ON l.transfer_id = t.transfer_id " +
                     "WHERE t.status = 'COMPLETED' AND l.ledger_entry_id IS NULL ORDER BY t.transfer_id";
        List<Issue> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong(1);
                out.add(new Issue("ORPHAN_TRANSFER", id, null, null, null, null,
                        "Completed transfer " + id + " has no ledger entries"));
            }
        }
        return out;
    }

    /** Check 5b: a ledger entry whose transfer does not exist. */
    public List<Issue> checkEntriesWithoutTransfer(Connection c) throws SQLException {
        String sql = "SELECT l.ledger_entry_id FROM ledger_entries l " +
                     "LEFT JOIN transfer t ON t.transfer_id = l.transfer_id " +
                     "WHERE t.transfer_id IS NULL ORDER BY l.ledger_entry_id";
        List<Issue> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong(1);
                out.add(new Issue("ORPHAN_ENTRY", null, null, id, null, null,
                        "Ledger entry " + id + " has no transfer"));
            }
        }
        return out;
    }

    // ======================= READS for the API =======================

    public RunView findRun(long runId) {
        List<RunView> rows = jdbc.query(
            "SELECT report_id, started_at, ended_at, status, problem_count " +
            "FROM reconciliation_runs WHERE report_id = ?",
            (rs, i) -> new RunView(
                rs.getLong("report_id"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getString("status"),
                rs.getLong("problem_count")),
            runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<IssueView> findIssues(long runId) {
        return jdbc.query(
            "SELECT issue_id, check_type, transfer_id, account_id, ledger_entry_id, " +
            "expected_value, actual_value, description " +
            "FROM reconciliation_issues WHERE report_id = ? ORDER BY issue_id",
            (rs, i) -> new IssueView(
                rs.getLong("issue_id"),
                rs.getString("check_type"),
                rs.getObject("transfer_id", Long.class),
                rs.getObject("account_id", Long.class),
                rs.getObject("ledger_entry_id", Long.class),
                rs.getObject("expected_value", Long.class),
                rs.getObject("actual_value", Long.class),
                rs.getString("description")),
            runId);
    }

    private void setLong(PreparedStatement ps, int index, Long v) throws SQLException {
        if (v == null) ps.setNull(index, Types.BIGINT);
        else ps.setLong(index, v);
    }
}
