package com.digitalwallet.PaymentsWalletLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserApiTest extends AbstractApiTest {

    @Test
    @DisplayName("POST /users returns 201 and a new id")
    void createUser_returns201() throws Exception {
        Resp r = post("/users",
                "{\"name\":\"Asha\",\"email\":\"asha_" + System.nanoTime() + "@example.com\","
                        + "\"phone\":\"9" + (100_000_000L + System.nanoTime() % 900_000_000L) + "\"}");

        assertEquals(201, r.status(), "body was: " + r.body());
        assertTrue(extractLong(r.body(), "(?:id|userId|user_id)") > 0);
    }

    @Test
    @DisplayName("Two users get two different ids")
    void createUser_idsAreDifferent() throws Exception {
        long first = createUser();
        long second = createUser();
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("User is really saved in the database")
    void createUser_isSavedInDb() throws Exception {
        long id = createUser();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, id);
        assertEquals(1, count);
    }
}
