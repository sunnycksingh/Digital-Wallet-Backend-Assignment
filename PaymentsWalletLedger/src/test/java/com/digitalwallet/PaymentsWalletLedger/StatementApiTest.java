package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class StatementApiTest extends AbstractApiTest {

    // Inserts one transfer from CASH_IN to the wallet, with 2 balanced entries.
    // minutesAgo lets us control the order in time.
    private long addTopUp(long walletId, long amountPaise, int minutesAgo, String note) {
        Long cashIn = jdbc.queryForObject(
            "SELECT account_id FROM accounts WHERE system_account_code = 'CASH_IN'", Long.class);

        Long transferId = jdbc.queryForObject(
            "INSERT INTO transfer (transfer_type, status, amount_paise, note, created_at) " +
            "VALUES ('TOP_UP', 'COMPLETED', ?, ?, now() - (? * interval '1 minute')) " +
            "RETURNING transfer_id", Long.class, amountPaise, note, minutesAgo);

        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount_paise, created_at) " +
            "VALUES (?, ?, 'DEBIT', ?, now() - (? * interval '1 minute'))",
            transferId, cashIn, amountPaise, minutesAgo);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount_paise, created_at) " +
            "VALUES (?, ?, 'CREDIT', ?, now() - (? * interval '1 minute'))",
            transferId, walletId, amountPaise, minutesAgo);
        return transferId;
    }

    private List<Long> transferIdsIn(String body) {
        List<Long> ids = new ArrayList<>();
        Matcher m = Pattern.compile("\"transferId\"\\s*:\\s*(\\d+)").matcher(body);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }

    @Test
    @DisplayName("A new wallet has an empty statement")
    void emptyStatement() throws Exception {
        long walletId = createWallet(createUser());

        Resp r = get("/wallets/" + walletId + "/statement");

        assertEquals(200, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"count\":0"));
        assertTrue(r.body().contains("\"entries\":[]"));
    }

    @Test
    @DisplayName("Statement shows the entry details")
    void showsEntryDetails() throws Exception {
        long walletId = createWallet(createUser());
        addTopUp(walletId, 50000, 1, "first top-up");

        Resp r = get("/wallets/" + walletId + "/statement");

        assertEquals(200, r.status());
        assertTrue(r.body().contains("\"transferType\":\"TOP_UP\""));
        assertTrue(r.body().contains("\"direction\":\"CREDIT\""));
        assertTrue(r.body().contains("\"amountPaise\":50000"));
        assertTrue(r.body().contains("\"amountRupees\":\"500.00\""));
        assertTrue(r.body().contains("\"note\":\"first top-up\""));
    }

    @Test
    @DisplayName("Newest entry comes first")
    void newestFirst() throws Exception {
        long walletId = createWallet(createUser());
        long oldest = addTopUp(walletId, 100, 30, "oldest");
        long middle = addTopUp(walletId, 200, 20, "middle");
        long newest = addTopUp(walletId, 300, 10, "newest");

        Resp r = get("/wallets/" + walletId + "/statement");

        assertEquals(List.of(newest, middle, oldest), transferIdsIn(r.body()));
    }

    @Test
    @DisplayName("limit and offset page through the entries")
    void pagination() throws Exception {
        long walletId = createWallet(createUser());
        long t1 = addTopUp(walletId, 100, 50, "a");
        long t2 = addTopUp(walletId, 100, 40, "b");
        long t3 = addTopUp(walletId, 100, 30, "c");
        long t4 = addTopUp(walletId, 100, 20, "d");
        long t5 = addTopUp(walletId, 100, 10, "e");

        List<Long> page1 = transferIdsIn(get("/wallets/" + walletId + "/statement?limit=2&offset=0").body());
        List<Long> page2 = transferIdsIn(get("/wallets/" + walletId + "/statement?limit=2&offset=2").body());
        List<Long> page3 = transferIdsIn(get("/wallets/" + walletId + "/statement?limit=2&offset=4").body());

        assertEquals(List.of(t5, t4), page1);
        assertEquals(List.of(t3, t2), page2);
        assertEquals(List.of(t1), page3);
    }

    @Test
    @DisplayName("Counterparty is the other account of the transfer (CASH_IN)")
    void counterpartyShown() throws Exception {
        long walletId = createWallet(createUser());
        addTopUp(walletId, 100, 1, "x");
        Long cashIn = jdbc.queryForObject(
            "SELECT account_id FROM accounts WHERE system_account_code = 'CASH_IN'", Long.class);

        Resp r = get("/wallets/" + walletId + "/statement");

        assertTrue(r.body().contains("\"counterpartyAccountId\":" + cashIn), "body was: " + r.body());
    }

    @Test
    @DisplayName("A wallet only sees its own entries")
    void onlyOwnEntries() throws Exception {
        long walletA = createWallet(createUser());
        long walletB = createWallet(createUser());
        long forA = addTopUp(walletA, 100, 2, "for A");
        addTopUp(walletB, 100, 1, "for B");

        List<Long> ids = transferIdsIn(get("/wallets/" + walletA + "/statement").body());

        assertEquals(List.of(forA), ids);
    }

    @Test
    @DisplayName("limit above 100 returns 400")
    void limitTooBig() throws Exception {
        long walletId = createWallet(createUser());
        assertEquals(400, get("/wallets/" + walletId + "/statement?limit=101").status());
    }

    @Test
    @DisplayName("limit 0 and negative offset return 400")
    void badLimitAndOffset() throws Exception {
        long walletId = createWallet(createUser());
        assertEquals(400, get("/wallets/" + walletId + "/statement?limit=0").status());
        assertEquals(400, get("/wallets/" + walletId + "/statement?offset=-1").status());
    }

    @Test
    @DisplayName("Unknown wallet returns 404")
    void unknownWallet() throws Exception {
        assertEquals(404, get("/wallets/999999999/statement").status());
    }

    @Test
    @DisplayName("System accounts are hidden and return 404")
    void systemAccountHidden() throws Exception {
        Long cashIn = jdbc.queryForObject(
            "SELECT account_id FROM accounts WHERE system_account_code = 'CASH_IN'", Long.class);
        assertEquals(404, get("/wallets/" + cashIn + "/statement").status());
    }

    @Test
    @DisplayName("Ledger rows cannot be updated or deleted")
    void ledgerIsAppendOnly() throws Exception {
        long walletId = createWallet(createUser());
        addTopUp(walletId, 100, 1, "x");

        assertThrows(Exception.class, () ->
            jdbc.update("UPDATE ledger_entries SET amount_paise = 1 WHERE account_id = ?", walletId));
        assertThrows(Exception.class, () ->
            jdbc.update("DELETE FROM ledger_entries WHERE account_id = ?", walletId));
    }
}