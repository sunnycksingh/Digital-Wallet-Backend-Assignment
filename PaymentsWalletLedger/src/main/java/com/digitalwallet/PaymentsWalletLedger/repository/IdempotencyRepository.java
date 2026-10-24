package com.digitalwallet.PaymentsWalletLedger.repository;

import com.digitalwallet.PaymentsWalletLedger.dto.TransferResponse;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Plain SQL for the idempotency_keys table. Always uses the transaction's own Connection. */
@Repository
public class IdempotencyRepository {

    public record Existing(String status, String requestHash, Integer responseCode, String responseBody) {}

    /**
     * Tries to insert the key. Returns the new row id, or null if this (user, key) already exists.
     * If another transaction is inserting the same key right now, this call WAITS until that one
     * commits or rolls back. That wait is how parallel duplicates are stopped.
     */
    public Long insertIfAbsent(Connection c, long userId, UUID key, String hash) throws SQLException {
        String sql = "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) " +
                     "VALUES (?, ?, ?) " +
                     "ON CONFLICT (user_id, idempotency_key) DO NOTHING " +
                     "RETURNING idempotency_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setObject(2, key);
            ps.setString(3, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public Existing find(Connection c, long userId, UUID key) throws SQLException {
        String sql = "SELECT status, request_hash, response_code, response_body::text " +
                     "FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setObject(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Existing(rs.getString(1), rs.getString(2),
                        rs.getObject(3, Integer.class), rs.getString(4));
            }
        }
    }

    /**
     * Saves the response on the key row and returns the saved JSON text.
     * The caller sends THIS text back, so the first response and every replay are identical.
     */
    public String complete(Connection c, long idempotencyId, int code, TransferResponse t) throws SQLException {
        String sql =
            "UPDATE idempotency_keys SET status = 'COMPLETED', response_code = ?, " +
            "response_body = jsonb_build_object(" +
            "  'transferId', ?::bigint, 'transferType', ?::text, 'status', ?::text, " +
            "  'amountPaise', ?::bigint, 'amountRupees', ?::text, " +
            "  'originalTransferId', ?::bigint, 'note', ?::text, 'createdAt', ?::timestamptz) " +
            "WHERE idempotency_id = ? RETURNING response_body::text";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, code);
            ps.setLong(2, t.transferId());
            ps.setString(3, t.transferType());
            ps.setString(4, t.status());
            ps.setLong(5, t.amountPaise());
            ps.setString(6, t.amountRupees());
            if (t.originalTransferId() == null) ps.setNull(7, Types.BIGINT);
            else ps.setLong(7, t.originalTransferId());
            ps.setString(8, t.note());
            ps.setObject(9, t.createdAt() == null ? null : t.createdAt().toString(), Types.VARCHAR);
            ps.setLong(10, idempotencyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
