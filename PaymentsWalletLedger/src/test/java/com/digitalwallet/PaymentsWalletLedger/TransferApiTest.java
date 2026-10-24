package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class TransferApiTest extends AbstractApiTest {

    // ---------- helpers ----------
    private long balance(long walletId) {
        return jdbc.queryForObject(
            "SELECT balance_paise FROM accounts WHERE account_id = ?", Long.class, walletId);
    }

    private long systemId(String code) {
        return jdbc.queryForObject(
            "SELECT account_id FROM accounts WHERE system_account_code = ?", Long.class, code);
    }

    private Resp topUp(long walletId, long paise) throws Exception {
        return post("/transfers/topup",
            "{\"walletId\":" + walletId + ",\"amountPaise\":" + paise + ",\"note\":\"test\"}");
    }

    private Resp send(long from, long to, long paise) throws Exception {
        return post("/transfers",
            "{\"fromWalletId\":" + from + ",\"toWalletId\":" + to + ",\"amountPaise\":" + paise + "}");
    }

    private Resp withdraw(long walletId, long paise) throws Exception {
        return post("/transfers/withdraw",
            "{\"walletId\":" + walletId + ",\"amountPaise\":" + paise + "}");
    }

    private long fundedWallet(long paise) throws Exception {
        long walletId = createWallet(createUser());
        assertEquals(201, topUp(walletId, paise).status());
        return walletId;
    }

    private long countTransfersFor(long walletId) {
        return jdbc.queryForObject(
            "SELECT COUNT(DISTINCT transfer_id) FROM ledger_entries WHERE account_id = ?",
            Long.class, walletId);
    }

    private long debitMinusCredit(long transferId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount_paise ELSE -amount_paise END),0) " +
            "FROM ledger_entries WHERE transfer_id = ?", Long.class, transferId);
    }

    // ---------- top-up ----------
    @Test
    @DisplayName("Top-up returns 201 and adds money")
    void topUp_works() throws Exception {
        long wallet = createWallet(createUser());

        Resp r = topUp(wallet, 50000);

        assertEquals(201, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"transferType\":\"TOP_UP\""));
        assertTrue(r.body().contains("\"status\":\"COMPLETED\""));
        assertEquals(50000, balance(wallet));
        assertEquals(0, debitMinusCredit(extractLong(r.body(), "transferId")));
    }

    @Test
    @DisplayName("Top-up lowers CASH_IN (system accounts may go negative)")
    void topUp_cashInGoesDown() throws Exception {
        long cashIn = systemId("CASH_IN");
        long before = balance(cashIn);
        long wallet = createWallet(createUser());

        topUp(wallet, 12345);

        assertEquals(before - 12345, balance(cashIn));
    }

    @Test
    @DisplayName("Top-up with amount 0, negative or missing returns 422")
    void topUp_badAmount() throws Exception {
        long wallet = createWallet(createUser());
        assertEquals(422, topUp(wallet, 0).status());
        assertEquals(422, topUp(wallet, -5).status());
        assertEquals(422, post("/transfers/topup", "{\"walletId\":" + wallet + "}").status());
        assertEquals(0, balance(wallet));
    }

    @Test
    @DisplayName("Top-up of an unknown wallet or a system account returns 422")
    void topUp_badWallet() throws Exception {
        assertEquals(422, topUp(999999999L, 100).status());
        assertEquals(422, topUp(systemId("CASH_OUT"), 100).status());
    }

    // ---------- peer transfer ----------
    @Test
    @DisplayName("Sending money moves it from one wallet to the other")
    void send_works() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());

        Resp r = send(a, b, 3000);

        assertEquals(201, r.status(), "body was: " + r.body());
        assertEquals(7000, balance(a));
        assertEquals(3000, balance(b));
        assertEquals(0, debitMinusCredit(extractLong(r.body(), "transferId")));
    }

    @Test
    @DisplayName("Sending more than the balance returns 422 and nothing changes")
    void send_insufficientFunds() throws Exception {
        long a = fundedWallet(1000);
        long b = createWallet(createUser());
        long transfersBefore = countTransfersFor(a);

        Resp r = send(a, b, 1001);

        assertEquals(422, r.status());
        assertTrue(r.body().contains("INSUFFICIENT_FUNDS"));
        assertTrue(r.body().contains("request_id"));
        assertEquals(1000, balance(a));
        assertEquals(0, balance(b));
        assertEquals(transfersBefore, countTransfersFor(a));   // no trace left
    }

    @Test
    @DisplayName("Sending to the same wallet returns 422")
    void send_sameWallet() throws Exception {
        long a = fundedWallet(1000);
        assertEquals(422, send(a, a, 100).status());
    }

    @Test
    @DisplayName("Sending to an unknown wallet returns 422 and keeps the money")
    void send_unknownReceiver() throws Exception {
        long a = fundedWallet(1000);
        assertEquals(422, send(a, 999999999L, 100).status());
        assertEquals(1000, balance(a));
    }

    // ---------- withdraw ----------
    @Test
    @DisplayName("Withdraw takes money out and raises CASH_OUT")
    void withdraw_works() throws Exception {
        long cashOut = systemId("CASH_OUT");
        long before = balance(cashOut);
        long wallet = fundedWallet(5000);

        Resp r = withdraw(wallet, 2000);

        assertEquals(201, r.status(), "body was: " + r.body());
        assertEquals(3000, balance(wallet));
        assertEquals(before + 2000, balance(cashOut));
    }

    @Test
    @DisplayName("Withdrawing more than the balance returns 422")
    void withdraw_insufficientFunds() throws Exception {
        long wallet = fundedWallet(500);
        assertEquals(422, withdraw(wallet, 501).status());
        assertEquals(500, balance(wallet));
    }

    // ---------- reverse ----------
    @Test
    @DisplayName("Reversing a transfer puts the money back")
    void reverse_works() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        long transferId = extractLong(send(a, b, 3000).body(), "transferId");

        Resp r = post("/transfers/" + transferId + "/reverse", "{}");

        assertEquals(201, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"transferType\":\"REVERSAL\""));
        assertTrue(r.body().contains("\"originalTransferId\":" + transferId));
        assertEquals(10000, balance(a));
        assertEquals(0, balance(b));
    }

    @Test
    @DisplayName("Reversing the same transfer twice returns 409")
    void reverse_twice() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        long transferId = extractLong(send(a, b, 3000).body(), "transferId");

        assertEquals(201, post("/transfers/" + transferId + "/reverse", "{}").status());
        assertEquals(409, post("/transfers/" + transferId + "/reverse", "{}").status());
        assertEquals(10000, balance(a));   // money was not returned twice
    }

    @Test
    @DisplayName("A reversal cannot be reversed")
    void reverse_aReversal() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        long transferId = extractLong(send(a, b, 3000).body(), "transferId");
        long reversalId = extractLong(post("/transfers/" + transferId + "/reverse", "{}").body(), "transferId");

        assertEquals(422, post("/transfers/" + reversalId + "/reverse", "{}").status());
    }

    @Test
    @DisplayName("Reversal fails with 422 if the receiver already spent the money")
    void reverse_receiverSpent() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        long c = createWallet(createUser());
        long transferId = extractLong(send(a, b, 3000).body(), "transferId");
        assertEquals(201, send(b, c, 3000).status());   // B spends it all

        Resp r = post("/transfers/" + transferId + "/reverse", "{}");

        assertEquals(422, r.status());
        assertEquals(7000, balance(a));
    }

    @Test
    @DisplayName("Reversing an unknown transfer returns 404")
    void reverse_unknown() throws Exception {
        assertEquals(404, post("/transfers/999999999/reverse", "{}").status());
    }

    // ---------- GET /transfers/{id} ----------
    @Test
    @DisplayName("GET transfer shows 2 entries and the status history")
    void getTransfer_details() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        long transferId = extractLong(send(a, b, 3000).body(), "transferId");

        Resp r = get("/transfers/" + transferId);

        assertEquals(200, r.status(), "body was: " + r.body());
        assertEquals(2, r.body().split("\"ledgerEntryId\"", -1).length - 1);
        assertTrue(r.body().contains("\"statusHistory\""));
        assertTrue(r.body().contains("\"newStatus\":\"COMPLETED\""));
    }

    @Test
    @DisplayName("GET unknown transfer returns 404")
    void getTransfer_unknown() throws Exception {
        assertEquals(404, get("/transfers/999999999").status());
    }

    // ---------- THE STAMPEDE ----------
    @RepeatedTest(value = 20, name = "Stampede {currentRepetition}/{totalRepetitions}")
    @DisplayName("100 parallel Rs10 transfers from a Rs500 wallet: exactly 50 succeed")
    void stampede() throws Exception {
        long source = fundedWallet(50000);                 // Rs 500
        long target = createWallet(createUser());

        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(pool.submit(() -> {
                start.await();                              // everyone starts together
                return send(source, target, 1000).status(); // Rs 10
            }));
        }
        start.countDown();

        int success = 0, rejected = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) success++;
            else if (status == 422) rejected++;
        }
        pool.shutdown();

        assertEquals(50, success, "successful transfers");
        assertEquals(50, rejected, "rejected transfers");
        assertEquals(0, balance(source), "source must end at 0");
        assertEquals(50000, balance(target));

        long diff = jdbc.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount_paise ELSE -amount_paise END),0) " +
            "FROM ledger_entries WHERE transfer_id IN " +
            "(SELECT transfer_id FROM ledger_entries WHERE account_id = ?)", Long.class, source);
        assertEquals(0, diff, "debits must equal credits");
    }
}
