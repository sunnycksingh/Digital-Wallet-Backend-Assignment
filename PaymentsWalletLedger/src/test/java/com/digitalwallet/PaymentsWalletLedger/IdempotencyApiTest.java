package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyApiTest extends AbstractApiTest {

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

    private String sendJson(long from, long to, long paise) {
        return "{\"fromWalletId\":" + from + ",\"toWalletId\":" + to + ",\"amountPaise\":" + paise + "}";
    }

    /** number of transfers that touch this wallet (the top-up counts as 1) */
    private long transfersTouching(long walletId) {
        return jdbc.queryForObject(
            "SELECT COUNT(DISTINCT transfer_id) FROM ledger_entries WHERE account_id = ?",
            Long.class, walletId);
    }

    // ---------- header rules ----------
    @Test
    @DisplayName("Missing Idempotency-Key returns 400")
    void missingKey_400() throws Exception {
        long a = fundedWallet(5000);
        long b = createWallet(createUser());

        Resp r = postRaw("/transfers", sendJson(a, b, 100), null);

        assertEquals(400, r.status(), "body was: " + r.body());
        assertTrue(r.body().contains("MISSING_IDEMPOTENCY_KEY"));
        assertEquals(5000, balance(a));
    }

    @Test
    @DisplayName("A key that is not a UUID returns 400")
    void badKey_400() throws Exception {
        long a = fundedWallet(5000);
        long b = createWallet(createUser());

        Resp r = postRaw("/transfers", sendJson(a, b, 100), "not-a-uuid");

        assertEquals(400, r.status());
        assertEquals(5000, balance(a));
    }

    // ---------- the 3 acceptance tests ----------
    @Test
    @DisplayName("Same request sent 5 times: 1 transfer and 5 identical responses")
    void sameRequest5Times() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        String key = UUID.randomUUID().toString();
        String body = sendJson(a, b, 1000);

        List<Resp> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) responses.add(postRaw("/transfers", body, key));

        Set<String> distinctBodies = new HashSet<>();
        for (Resp r : responses) {
            assertEquals(201, r.status(), "body was: " + r.body());
            distinctBodies.add(r.body());
        }
        assertEquals(1, distinctBodies.size(), "all 5 responses must be identical");
        assertEquals(2, transfersTouching(a));      // the top-up + exactly ONE send
        assertEquals(9000, balance(a));             // charged once
        assertEquals(1000, balance(b));
    }

    @Test
    @DisplayName("Same key sent 10 times in parallel: exactly 1 transfer")
    void sameKey10InParallel() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        String key = UUID.randomUUID().toString();
        String body = sendJson(a, b, 1000);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/transfers", body, key);
            }));
        }
        start.countDown();

        Set<String> distinctBodies = new HashSet<>();
        for (Future<Resp> f : futures) {
            Resp r = f.get();
            assertEquals(201, r.status(), "body was: " + r.body());
            distinctBodies.add(r.body());
        }
        pool.shutdown();

        assertEquals(1, distinctBodies.size(), "all responses must be identical");
        assertEquals(2, transfersTouching(a));      // the top-up + exactly ONE send
        assertEquals(9000, balance(a));
        assertEquals(1000, balance(b));
    }

    @Test
    @DisplayName("Same key with a different amount returns 422 and no money moves")
    void sameKeyDifferentAmount() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        String key = UUID.randomUUID().toString();

        assertEquals(201, postRaw("/transfers", sendJson(a, b, 1000), key).status());
        Resp second = postRaw("/transfers", sendJson(a, b, 2000), key);

        assertEquals(422, second.status(), "body was: " + second.body());
        assertTrue(second.body().contains("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(9000, balance(a));             // only the first 1000 moved
        assertEquals(1000, balance(b));
        assertEquals(2, transfersTouching(a));
    }

    // ---------- extra checks ----------
    @Test
    @DisplayName("Different keys are different requests: two transfers")
    void differentKeys_twoTransfers() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());

        assertEquals(201, postRaw("/transfers", sendJson(a, b, 1000), UUID.randomUUID().toString()).status());
        assertEquals(201, postRaw("/transfers", sendJson(a, b, 1000), UUID.randomUUID().toString()).status());

        assertEquals(8000, balance(a));
        assertEquals(3, transfersTouching(a));
    }

    @Test
    @DisplayName("Top-up retried with the same key adds money only once")
    void topUpRetry() throws Exception {
        long wallet = createWallet(createUser());
        String key = UUID.randomUUID().toString();
        String body = "{\"walletId\":" + wallet + ",\"amountPaise\":2500}";

        Resp first = postRaw("/transfers/topup", body, key);
        Resp second = postRaw("/transfers/topup", body, key);

        assertEquals(201, first.status());
        assertEquals(first.body(), second.body());
        assertEquals(2500, balance(wallet));
    }

    @Test
    @DisplayName("Reverse retried with the same key reverses only once")
    void reverseRetry() throws Exception {
        long a = fundedWallet(10000);
        long b = createWallet(createUser());
        long transferId = extractLong(post("/transfers", sendJson(a, b, 3000)).body(), "transferId");
        String key = UUID.randomUUID().toString();

        Resp first = postRaw("/transfers/" + transferId + "/reverse", "{}", key);
        Resp second = postRaw("/transfers/" + transferId + "/reverse", "{}", key);

        assertEquals(201, first.status(), "body was: " + first.body());
        assertEquals(201, second.status());
        assertEquals(first.body(), second.body());
        assertEquals(10000, balance(a));
        assertEquals(0, balance(b));
    }

    @Test
    @DisplayName("A failed request (not enough money) can be retried with the same key after funding")
    void failedRequestCanBeRetried() throws Exception {
        long a = fundedWallet(500);
        long b = createWallet(createUser());
        String key = UUID.randomUUID().toString();
        String body = sendJson(a, b, 1000);

        assertEquals(422, postRaw("/transfers", body, key).status());   // rolled back, key not kept

        assertEquals(201, post("/transfers/topup",
            "{\"walletId\":" + a + ",\"amountPaise\":1000}").status());
        assertEquals(201, postRaw("/transfers", body, key).status());   // same key now works
        assertEquals(500, balance(a));
    }

    // ---------- the stampede must still pass, now with keys ----------
    @RepeatedTest(value = 5, name = "Stampede with keys {currentRepetition}/{totalRepetitions}")
    @DisplayName("100 parallel Rs10 transfers with different keys: exactly 50 succeed")
    void stampedeStillWorks() throws Exception {
        long source = fundedWallet(50000);
        long target = createWallet(createUser());

        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post("/transfers", sendJson(source, target, 1000)).status();
            }));
        }
        start.countDown();

        int success = 0;
        for (Future<Integer> f : futures) if (f.get() == 201) success++;
        pool.shutdown();

        assertEquals(50, success);
        assertEquals(0, balance(source));
        assertEquals(50000, balance(target));
    }
}
