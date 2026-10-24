package com.digitalwallet.PaymentsWalletLedger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiTest {

    // Tests do not run main(), so we repeat the timezone fix here
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    // ---- small result holder ----
    record Resp(int status, String body) {}

    // ---- HTTP helpers ----

    /** POST. For /transfers paths a fresh random Idempotency-Key is added automatically. */
    Resp post(String path, String json) throws Exception {
        String key = path.startsWith("/transfers") ? UUID.randomUUID().toString() : null;
        return postRaw(path, json, key);
    }

    /** POST with an exact Idempotency-Key (pass null to send NO header). */
    Resp postRaw(String path, String json, String idempotencyKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(res.statusCode(), normalize(path, res.body()));
    }

    Resp get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(res.statusCode(), res.body());
    }

    /**
     * Transfer responses come from Postgres jsonb, which writes {"a": 1, "b": 2} with spaces.
     * Remove the spaces so the contains("\"status\":\"COMPLETED\"") style checks keep working.
     */
    private String normalize(String path, String body) {
        if (body == null || !path.startsWith("/transfers")) return body;
        return body.replace("\": ", "\":").replace(", \"", ",\"");
    }

    // ---- data helpers ----
    /** Creates a brand new user and returns its id. */
    long createUser() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long phone = 1_000_000_000L + ThreadLocalRandom.current().nextLong(9_000_000_000L);
        String json = String.format(
                "{\"name\":\"Test %s\",\"email\":\"test_%s@example.com\",\"phone\":\"%d\"}",
                unique, unique, phone);
        Resp r = post("/users", json);
        return extractLong(r.body(), "(?:id|userId|user_id)");
    }

    /** Creates a MAIN wallet for the user and returns the wallet (account) id. */
    long createWallet(long userId) throws Exception {
        Resp r = post("/users/" + userId + "/wallets", "{\"walletType\":\"MAIN\"}");
        return extractLong(r.body(), "accountId");
    }

    long extractLong(String json, String fieldRegex) {
        Matcher m = Pattern.compile("\"" + fieldRegex + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("Field " + fieldRegex + " not found in: " + json);
        }
        return Long.parseLong(m.group(1));
    }
}
