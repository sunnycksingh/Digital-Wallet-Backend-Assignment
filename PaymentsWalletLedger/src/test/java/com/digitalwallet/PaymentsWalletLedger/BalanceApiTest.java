package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BalanceApiTest extends AbstractApiTest {

    @Test
    @DisplayName("A new wallet has a balance of 0")
    void newWallet_balanceIsZero() throws Exception {
        long walletId = createWallet(createUser());

        Resp r = get("/wallets/" + walletId + "/balance");

        assertEquals(200, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"balancePaise\":0"));
        assertTrue(r.body().contains("\"balanceRupees\":\"0.00\""));
        assertTrue(r.body().contains("\"currency\":\"INR\""));
    }

    @Test
    @DisplayName("50000 paise is shown as 500.00 rupees")
    void balance_showsRupees() throws Exception {
        long walletId = createWallet(createUser());
        jdbc.update("UPDATE accounts SET balance_paise = ? WHERE account_id = ?", 50000L, walletId);

        Resp r = get("/wallets/" + walletId + "/balance");

        assertEquals(200, r.status());
        assertTrue(r.body().contains("\"balancePaise\":50000"));
        assertTrue(r.body().contains("\"balanceRupees\":\"500.00\""));
    }

    @Test
    @DisplayName("5 paise is shown as 0.05, not 0.5")
    void balance_smallAmountHasTwoDecimals() throws Exception {
        long walletId = createWallet(createUser());
        jdbc.update("UPDATE accounts SET balance_paise = ? WHERE account_id = ?", 5L, walletId);

        Resp r = get("/wallets/" + walletId + "/balance");

        assertTrue(r.body().contains("\"balanceRupees\":\"0.05\""), "body was: " + r.body());
    }

    @Test
    @DisplayName("A wallet that does not exist returns 404")
    void unknownWallet_returns404() throws Exception {
        Resp r = get("/wallets/999999999/balance");

        assertEquals(404, r.status());
    }

    @Test
    @DisplayName("System accounts are hidden and return 404")
    void systemAccount_returns404() throws Exception {
        Long cashInId = jdbc.queryForObject(
                "SELECT account_id FROM accounts WHERE system_account_code = 'CASH_IN'", Long.class);

        Resp r = get("/wallets/" + cashInId + "/balance");

        assertEquals(404, r.status());
    }

    @Test
    @DisplayName("A non-number id returns 400")
    void nonNumberId_returns400() throws Exception {
        Resp r = get("/wallets/abc/balance");

        assertEquals(400, r.status());
    }
}