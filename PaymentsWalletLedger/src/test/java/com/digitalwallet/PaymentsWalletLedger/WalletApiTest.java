package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WalletApiTest extends AbstractApiTest {

    @Test
    @DisplayName("POST /users/{id}/wallets opens a MAIN wallet with 0 balance")
    void openMainWallet_returns201() throws Exception {
        long userId = createUser();

        Resp r = post("/users/" + userId + "/wallets", "{\"walletType\":\"MAIN\"}");

        assertEquals(201, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("\"balancePaise\":0"));
        assertTrue(r.body().contains("\"currency\":\"INR\""));
        assertTrue(r.body().contains("\"walletType\":\"MAIN\""));
    }

    @Test
    @DisplayName("One user can have both a MAIN and a SAVING wallet")
    void userCanHaveTwoWalletTypes() throws Exception {
        long userId = createUser();

        Resp main = post("/users/" + userId + "/wallets", "{\"walletType\":\"MAIN\"}");
        Resp saving = post("/users/" + userId + "/wallets", "{\"walletType\":\"SAVING\"}");

        assertEquals(201, main.status());
        assertEquals(201, saving.status());
    }

    @Test
    @DisplayName("Opening the same wallet type twice returns 409")
    void duplicateWallet_returns409() throws Exception {
        long userId = createUser();
        post("/users/" + userId + "/wallets", "{\"walletType\":\"MAIN\"}");

        Resp second = post("/users/" + userId + "/wallets", "{\"walletType\":\"MAIN\"}");

        assertEquals(409, second.status());
    }

    @Test
    @DisplayName("A wrong wallet type returns 400")
    void invalidWalletType_returns400() throws Exception {
        long userId = createUser();

        Resp r = post("/users/" + userId + "/wallets", "{\"walletType\":\"GOLD\"}");

        assertEquals(400, r.status());
    }

    @Test
    @DisplayName("A missing wallet type returns 400")
    void missingWalletType_returns400() throws Exception {
        long userId = createUser();

        Resp r = post("/users/" + userId + "/wallets", "{}");

        assertEquals(400, r.status());
    }

    @Test
    @DisplayName("A user that does not exist returns 404")
    void unknownUser_returns404() throws Exception {
        Resp r = post("/users/999999999/wallets", "{\"walletType\":\"MAIN\"}");

        assertEquals(404, r.status());
    }
}