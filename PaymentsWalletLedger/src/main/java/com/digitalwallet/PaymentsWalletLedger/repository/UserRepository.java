package com.digitalwallet.PaymentsWalletLedger.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.digitalwallet.PaymentsWalletLedger.dto.CreateUserRequest;
import com.digitalwallet.PaymentsWalletLedger.model.User;

import java.util.List;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(CreateUserRequest r) {
        return jdbc.queryForObject(
            "INSERT INTO users (username, email, phone) VALUES (?, ?, ?) RETURNING user_id",
            Long.class,
            r.name(), r.email(), r.phone());
    }

    public User findById(long id) {
        List<User> rows = jdbc.query(
            "SELECT user_id, username, email, phone, kyc_status FROM users WHERE user_id = ?",
            (rs, i) -> new User(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("kyc_status")),
            id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
