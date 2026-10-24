package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationApiTest extends AbstractApiTest {

    // ---------- helpers ----------
    private long fundedWallet(long paise) throws Exception {
        long walletId = createWallet(createUser());
        assertEquals(201, post("/transfers/topup",
            "{\"walletId\":" + walletId + ",\"amountPaise\":" + paise + "}").status());
        return walletId;
    }

    private long send(long from, long to, long paise) throws Exception {
        Resp r = post("/transfers",
            "{\"fromWalletId\":" + from + ",\"toWalletId\":" + to + ",\"amountPaise\":" + paise + "}");
        assertEquals(201, r.status(), "body was: " + r.body());
        return extractLong(r.body(), "transferId");
    }

    /** Runs the job through the API and returns the run id. */
    private long runJob() throws Exception {
        Resp r = postRaw("/admin/reconciliation/run", "", null);
        assertEquals(202, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"status\":\"COMPLETED\""), "body was: " + r.body());
        return extractLong(r.body(), "runId");
    }

    /** The problems of one run as simple text keys: TYPE|transferId|accountId */
    private Set<String> issuesOf(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT check_type, transfer_id, account_id FROM reconciliation_issues WHERE report_id = ?", runId);
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            keys.add(row.get("check_type") + "|" + row.get("transfer_id") + "|" + row.get("account_id"));
        }
        return keys;
    }

    // ---------- endpoint basics ----------
    @Test
    @DisplayName("POST /admin/reconciliation/run returns 202 and the run can be read back")
    void runAndRead() throws Exception {
        long runId = runJob();

        Resp r = get("/admin/reconciliation/runs/" + runId);

        assertEquals(200, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"issues\""));
        assertTrue(r.body().contains("\"runId\":" + runId));
    }

    @Test
    @DisplayName("Unknown run returns 404")
    void unknownRun() throws Exception {
        assertEquals(404, get("/admin/reconciliation/runs/999999999").status());
    }

    @Test
    @DisplayName("A run is rejected with 409 while another run holds the lock")
    void onlyOneRunAtATime() throws Exception {
        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1 FROM job_locks WHERE job_name = 'reconciliation' FOR UPDATE");
            }

            Resp r = postRaw("/admin/reconciliation/run", "", null);   // must NOT wait

            assertEquals(409, r.status(), "body was: " + r.body());
            assertTrue(r.body().contains("RECONCILIATION_RUNNING"));
            c.rollback();
        }
    }

    // ---------- the acceptance test: corrupt 3 things, find them ----------
    @Test
    @DisplayName("Changing an entry, deleting a credit and changing a balance are all reported")
    void detectsCorruption() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        long c = createWallet(createUser());
        long d = fundedWallet(2000);

        long t1 = send(a, b, 1000);   // corruption 1: its credit amount will be changed
        long t2 = send(b, c, 500);    // corruption 2: its credit will be deleted

        Set<String> before = issuesOf(runJob());   // problems that already exist in the dev database

        long entry1 = jdbc.queryForObject(
            "SELECT ledger_entry_id FROM ledger_entries WHERE transfer_id = ? AND direction = 'CREDIT'",
            Long.class, t1);
        long entry2 = jdbc.queryForObject(
            "SELECT ledger_entry_id FROM ledger_entries WHERE transfer_id = ? AND direction = 'CREDIT'",
            Long.class, t2);

        try {
            // the append-only trigger must be switched off to corrupt the data (that is its job to prevent)
            jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_no_update_delete");

            jdbc.update("UPDATE ledger_entries SET amount_paise = amount_paise + 7 WHERE ledger_entry_id = ?", entry1);
            jdbc.update("DELETE FROM ledger_entries WHERE ledger_entry_id = ?", entry2);
            jdbc.update("UPDATE accounts SET balance_paise = balance_paise + 99 WHERE account_id = ?", d);

            Set<String> after = issuesOf(runJob());
            Set<String> found = new HashSet<>(after);
            found.removeAll(before);

            // Each corruption shows up in the check built for it...
            assertTrue(found.contains("TRANSFER_BALANCE|" + t1 + "|null"), "entry change not found: " + found);
            assertTrue(found.contains("TRANSFER_BALANCE|" + t2 + "|null"), "deleted credit not found: " + found);
            assertTrue(found.contains("STORED_BALANCE|null|" + d), "balance change not found: " + found);

            // ...and nothing unrelated is reported. The entry changes also move the totals and the
            // stored-vs-computed balance of the 2 wallets whose entries changed (b and c).
            Set<String> expected = Set.of(
                "GLOBAL_BALANCE|null|null",
                "TRANSFER_BALANCE|" + t1 + "|null",
                "TRANSFER_BALANCE|" + t2 + "|null",
                "STORED_BALANCE|null|" + b,
                "STORED_BALANCE|null|" + c,
                "STORED_BALANCE|null|" + d);
            assertEquals(expected, found);
        } finally {
            // put everything back so later tests and runs start clean
            jdbc.update("UPDATE ledger_entries SET amount_paise = amount_paise - 7 WHERE ledger_entry_id = ?", entry1);
            jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount_paise) " +
                        "VALUES (?, ?, 'CREDIT', 500)", t2, c);
            jdbc.update("UPDATE accounts SET balance_paise = balance_paise - 99 WHERE account_id = ?", d);
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_no_update_delete");
        }

        // after the repair, none of the corruptions is reported any more
        Set<String> repaired = issuesOf(runJob());
        assertFalse(repaired.contains("TRANSFER_BALANCE|" + t1 + "|null"));
        assertFalse(repaired.contains("TRANSFER_BALANCE|" + t2 + "|null"));
        assertFalse(repaired.contains("STORED_BALANCE|null|" + d));
    }
}
