package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskApiTest extends AbstractApiTest {

    private static final long BIG = 5_000_000L;      // Rs 50,000 = the HIGH_AMOUNT_HOLD threshold
    private static final long FUND = 6_000_000L;

    // ---------- helpers ----------
    private long balance(long walletId) {
        return jdbc.queryForObject(
            "SELECT balance_paise FROM accounts WHERE account_id = ?", Long.class, walletId);
    }

    private long fundedWallet(long paise) throws Exception {
        long walletId = createWallet(createUser());
        assertEquals(201, post("/transfers/topup",
            "{\"walletId\":" + walletId + ",\"amountPaise\":" + paise + "}").status());
        return walletId;
    }

    private Resp send(long from, long to, long paise) throws Exception {
        return post("/transfers",
            "{\"fromWalletId\":" + from + ",\"toWalletId\":" + to + ",\"amountPaise\":" + paise + "}");
    }

    private Resp decide(long transferId, String decision) throws Exception {
        return postRaw("/admin/risk/" + transferId + "/decision",
            "{\"decision\":\"" + decision + "\",\"reason\":\"test\"}", null);
    }

    private String transferStatus(long transferId) {
        return jdbc.queryForObject("SELECT status FROM transfer WHERE transfer_id = ?", String.class, transferId);
    }

    private long entryCount(long transferId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?", Long.class, transferId);
    }

    private List<String> flagStatuses(long transferId) {
        return jdbc.queryForList("SELECT status FROM risk_flags WHERE transfer_id = ? ORDER BY risk_flag_id",
            String.class, transferId);
    }

    /** Sends one big transfer and returns its id. The transfer is HELD. */
    private long heldTransfer(long from, long to) throws Exception {
        Resp r = send(from, to, BIG);
        assertEquals(202, r.status(), "body was: " + r.body());
        return extractLong(r.body(), "transferId");
    }

    /** 11 small sends from a fresh wallet: the 11th is flagged (but still completes). Returns {wallet, other, 11th transfer}. */
    private long[] flaggedTransfer() throws Exception {
        long a = fundedWallet(100_000);
        long b = createWallet(createUser());
        for (int i = 0; i < 10; i++) assertEquals(201, send(a, b, 100).status());
        Resp r = send(a, b, 100);
        assertEquals(201, r.status(), "body was: " + r.body());
        return new long[]{a, b, extractLong(r.body(), "transferId")};
    }

    // ---------- HIGH_AMOUNT rule: HOLD ----------
    @Test
    @DisplayName("A big send is HELD: 202, no entries, no balance change, flag and audit saved")
    void bigSend_isHeld() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());

        long id = heldTransfer(a, b);

        assertTrue(send(a, b, 1).status() == 201);    // a small one still works
        assertEquals("HELD", transferStatus(id));
        assertEquals(0, entryCount(id));
        assertEquals(FUND - 1, balance(a));            // only the 1 paise moved
        assertEquals(1, balance(b));
        assertEquals(List.of("OPEN"), flagStatuses(id));
        assertEquals(1, (long) jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE transfer_id = ? AND new_status = 'HELD'", Long.class, id));
    }

    @Test
    @DisplayName("Just below the threshold is not held")
    void belowThreshold_notHeld() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());

        Resp r = send(a, b, BIG - 1);

        assertEquals(201, r.status(), "body was: " + r.body());
    }

    @Test
    @DisplayName("A big withdrawal is held too")
    void bigWithdraw_isHeld() throws Exception {
        long a = fundedWallet(FUND);

        Resp r = post("/transfers/withdraw", "{\"walletId\":" + a + ",\"amountPaise\":" + BIG + "}");

        assertEquals(202, r.status(), "body was: " + r.body());
        assertEquals(FUND, balance(a));
    }

    @Test
    @DisplayName("A big top-up is NOT held (rules only watch money leaving a wallet)")
    void bigTopUp_notHeld() throws Exception {
        long a = createWallet(createUser());
        assertEquals(201, post("/transfers/topup",
            "{\"walletId\":" + a + ",\"amountPaise\":" + (BIG * 2) + "}").status());
    }

    @Test
    @DisplayName("A big send without enough money is rejected with 422, not held")
    void bigSend_noFunds() throws Exception {
        long a = fundedWallet(1000);
        long b = createWallet(createUser());

        assertEquals(422, send(a, b, BIG).status());
    }

    @Test
    @DisplayName("The same key on a held transfer replays 202 and creates one transfer")
    void heldTransfer_idempotent() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        String key = UUID.randomUUID().toString();
        String body = "{\"fromWalletId\":" + a + ",\"toWalletId\":" + b + ",\"amountPaise\":" + BIG + "}";

        Resp first = postRaw("/transfers", body, key);
        Resp second = postRaw("/transfers", body, key);

        assertEquals(202, first.status());
        assertEquals(202, second.status());
        assertEquals(first.body(), second.body());
        assertEquals(1, (long) jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer WHERE from_account_id = ?", Long.class, a));
    }

    // ---------- admin queue ----------
    @Test
    @DisplayName("GET /admin/risk/queue lists the held transfer")
    void queueShowsHeld() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long id = heldTransfer(a, b);

        Resp r = get("/admin/risk/queue?limit=100");

        assertEquals(200, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"transferId\":" + id), "queue was: " + r.body());
        assertTrue(r.body().contains("HIGH_AMOUNT_HOLD"));
    }

    @Test
    @DisplayName("Queue with a bad limit returns 400")
    void queueBadLimit() throws Exception {
        assertEquals(400, get("/admin/risk/queue?limit=0").status());
        assertEquals(400, get("/admin/risk/queue?limit=1000").status());
    }

    // ---------- decisions on a HELD transfer ----------
    @Test
    @DisplayName("RELEASE moves the money and completes the transfer")
    void release_works() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long id = heldTransfer(a, b);

        Resp r = decide(id, "RELEASE");

        assertEquals(200, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"transferStatus\":\"COMPLETED\""));
        assertEquals("COMPLETED", transferStatus(id));
        assertEquals(2, entryCount(id));
        assertEquals(FUND - BIG, balance(a));
        assertEquals(BIG, balance(b));
        assertEquals(List.of("RELEASED"), flagStatuses(id));
    }

    @Test
    @DisplayName("Releasing twice returns 409 and moves money only once")
    void release_twice() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long id = heldTransfer(a, b);

        assertEquals(200, decide(id, "RELEASE").status());
        assertEquals(409, decide(id, "RELEASE").status());
        assertEquals(BIG, balance(b));
    }

    @Test
    @DisplayName("Release fails with 422 if the sender spent the money meanwhile")
    void release_noFundsAnymore() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long id = heldTransfer(a, b);
        assertEquals(201, post("/transfers/withdraw",
            "{\"walletId\":" + a + ",\"amountPaise\":2000000}").status());   // leaves 4,000,000 < 5,000,000

        Resp r = decide(id, "RELEASE");

        assertEquals(422, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("INSUFFICIENT_FUNDS"));
        assertEquals("HELD", transferStatus(id));
        assertEquals(0, balance(b));
    }

    @Test
    @DisplayName("REVERSE on a held transfer cancels it: FAILED, no money ever moved")
    void reverseHeld_cancels() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long id = heldTransfer(a, b);

        Resp r = decide(id, "REVERSE");

        assertEquals(200, r.status(), "body was: " + r.body());
        assertEquals("FAILED", transferStatus(id));
        assertEquals(FUND, balance(a));
        assertEquals(0, balance(b));
        assertEquals(List.of("REVERSED"), flagStatuses(id));
    }

    // ---------- VELOCITY rule: FLAG ----------
    @Test
    @DisplayName("The 11th send within 10 minutes is flagged but still completes")
    void velocity_flags() throws Exception {
        long[] f = flaggedTransfer();

        assertEquals("COMPLETED", transferStatus(f[2]));
        assertEquals(1, (long) jdbc.queryForObject(
            "SELECT COUNT(*) FROM risk_flags rf JOIN fraud_rules r ON r.rule_id = rf.rule_id " +
            "WHERE rf.transfer_id = ? AND r.rule_name = 'VELOCITY_FLAG'", Long.class, f[2]));
        assertEquals(List.of("OPEN"), flagStatuses(f[2]));
    }

    @Test
    @DisplayName("The 10th send is not flagged yet")
    void velocity_tenthNotFlagged() throws Exception {
        long a = fundedWallet(100_000);
        long b = createWallet(createUser());
        long last = 0;
        for (int i = 0; i < 10; i++) {
            last = extractLong(send(a, b, 100).body(), "transferId");
        }
        assertTrue(flagStatuses(last).isEmpty());
    }

    @Test
    @DisplayName("DISMISS closes the flag of a completed transfer")
    void dismiss_works() throws Exception {
        long[] f = flaggedTransfer();

        Resp r = decide(f[2], "DISMISS");

        assertEquals(200, r.status(), "body was: " + r.body());
        assertEquals(List.of("DISMISSED"), flagStatuses(f[2]));
        assertEquals(409, decide(f[2], "DISMISS").status());      // nothing left to dismiss
    }

    @Test
    @DisplayName("REVERSE on a flagged completed transfer creates a reversal and returns the money")
    void reverseFlagged_createsReversal() throws Exception {
        long[] f = flaggedTransfer();
        long before = balance(f[0]);

        Resp r = decide(f[2], "REVERSE");

        assertEquals(200, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"reversalTransferId\":"));
        assertFalse(r.body().contains("\"reversalTransferId\":null"));
        assertEquals(before + 100, balance(f[0]));
        assertEquals(List.of("REVERSED"), flagStatuses(f[2]));
    }

    @Test
    @DisplayName("DISMISS on a held transfer returns 409")
    void dismissHeld_409() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long id = heldTransfer(a, b);

        assertEquals(409, decide(id, "DISMISS").status());
        assertEquals("HELD", transferStatus(id));
    }

    // ---------- bad input ----------
    @Test
    @DisplayName("Invalid decision returns 422, unknown transfer returns 404")
    void badDecision() throws Exception {
        assertEquals(422, decide(1, "MAYBE").status());
        assertEquals(404, decide(999999999L, "RELEASE").status());
    }

    @Test
    @DisplayName("An unknown adminUserId returns 422")
    void unknownAdmin() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long id = heldTransfer(a, b);

        Resp r = postRaw("/admin/risk/" + id + "/decision",
            "{\"decision\":\"RELEASE\",\"adminUserId\":999999999}", null);

        assertEquals(422, r.status());
        assertEquals("HELD", transferStatus(id));
    }

    @Test
    @DisplayName("A real adminUserId is saved as ADMIN in the audit log")
    void adminIsAudited() throws Exception {
        long a = fundedWallet(FUND);
        long b = createWallet(createUser());
        long adminUser = createUser();
        long id = heldTransfer(a, b);

        Resp r = postRaw("/admin/risk/" + id + "/decision",
            "{\"decision\":\"RELEASE\",\"adminUserId\":" + adminUser + "}", null);

        assertEquals(200, r.status(), "body was: " + r.body());
        assertEquals(1, (long) jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE transfer_id = ? AND changed_by_type = 'ADMIN' " +
            "AND changed_by_user_id = ?", Long.class, id, adminUser));
    }
}
