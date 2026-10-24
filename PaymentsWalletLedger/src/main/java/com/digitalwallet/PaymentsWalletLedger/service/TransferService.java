package com.digitalwallet.PaymentsWalletLedger.service;

import com.digitalwallet.PaymentsWalletLedger.dto.*;
import com.digitalwallet.PaymentsWalletLedger.dto.RiskDtos.DecisionRequest;
import com.digitalwallet.PaymentsWalletLedger.dto.RiskDtos.DecisionResponse;
import com.digitalwallet.PaymentsWalletLedger.dto.RiskDtos.RiskQueueResponse;
import com.digitalwallet.PaymentsWalletLedger.dto.TransferDetailResponse.EntryView;
import com.digitalwallet.PaymentsWalletLedger.exception.ApiException;
import com.digitalwallet.PaymentsWalletLedger.repository.IdempotencyRepository;
import com.digitalwallet.PaymentsWalletLedger.repository.IdempotencyRepository.Existing;
import com.digitalwallet.PaymentsWalletLedger.repository.RiskRepository;
import com.digitalwallet.PaymentsWalletLedger.repository.TransferRepository;
import com.digitalwallet.PaymentsWalletLedger.repository.TransferRepository.Initiator;
import com.digitalwallet.PaymentsWalletLedger.repository.TransferRepository.LockedAccount;
import com.digitalwallet.PaymentsWalletLedger.repository.TransferRepository.TransferAccounts;
import com.digitalwallet.PaymentsWalletLedger.service.FraudService.RuleHit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

@Service
public class TransferService {

    /** One debit or credit line of a transfer. */
    private record Leg(long accountId, String direction, long amount) {}

    /** What post() produced. held = the fraud rules stopped it and no money moved. */
    private record PostResult(TransferResponse transfer, boolean held) {}

    @FunctionalInterface
    private interface TxWork<T> {
        T run(Connection c) throws SQLException;
    }

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final DataSource dataSource;
    private final TransferRepository repo;
    private final IdempotencyRepository idem;
    private final FraudService fraud;
    private final RiskRepository riskRepo;

    public TransferService(DataSource dataSource, TransferRepository repo, IdempotencyRepository idem,
                           FraudService fraud, RiskRepository riskRepo) {
        this.dataSource = dataSource;
        this.repo = repo;
        this.idem = idem;
        this.fraud = fraud;
        this.riskRepo = riskRepo;
    }

    // ------------------------------------------------------------------
    // 1. TOP-UP: CASH_IN -> wallet
    // ------------------------------------------------------------------
    public IdemResult topUp(String keyHeader, TopUpRequest r) {
        UUID key = parseKey(keyHeader);
        requireBody(r);
        Long walletId = requireId(r.walletId(), "walletId");
        long amount = requireAmount(r.amountPaise());
        String hash = sha256("TOPUP|" + walletId + "|" + amount + "|" + noteText(r.note()));

        return inTransaction(c -> {
            long userId = requireOwner(c, walletId);

            Long idemId = idem.insertIfAbsent(c, userId, key, hash);
            if (idemId == null) return replayOf(c, userId, key, hash);

            long cashIn = repo.systemAccountId(c, "CASH_IN");
            Map<Long, LockedAccount> locked = repo.lockAccounts(c, List.of(cashIn, walletId));
            LockedAccount wallet = requireWallet(locked, walletId);

            List<Leg> legs = List.of(
                    new Leg(cashIn, "DEBIT", amount),
                    new Leg(walletId, "CREDIT", amount));
            PostResult pr = post(c, "TOP_UP", legs, amount, r.note(), wallet.userId(), null,
                    "USER", wallet.userId(), "Top-up", locked);
            return finish(c, idemId, pr);
        });
    }

    // ------------------------------------------------------------------
    // 2. PEER TRANSFER: wallet -> wallet   (201 done, 202 held)
    // ------------------------------------------------------------------
    public IdemResult send(String keyHeader, TransferRequest r) {
        UUID key = parseKey(keyHeader);
        requireBody(r);
        Long from = requireId(r.fromWalletId(), "fromWalletId");
        Long to = requireId(r.toWalletId(), "toWalletId");
        long amount = requireAmount(r.amountPaise());
        if (from.equals(to)) {
            throw unprocessable("SAME_WALLET", "Cannot send money to the same wallet");
        }
        String hash = sha256("SEND|" + from + "|" + to + "|" + amount + "|" + noteText(r.note()));

        return inTransaction(c -> {
            long userId = requireOwner(c, from);

            Long idemId = idem.insertIfAbsent(c, userId, key, hash);
            if (idemId == null) return replayOf(c, userId, key, hash);

            Map<Long, LockedAccount> locked = repo.lockAccounts(c, List.of(from, to));
            LockedAccount sender = requireWallet(locked, from);
            requireWallet(locked, to);

            List<Leg> legs = List.of(
                    new Leg(from, "DEBIT", amount),
                    new Leg(to, "CREDIT", amount));
            PostResult pr = post(c, "PEER_TRANSFER", legs, amount, r.note(), sender.userId(), null,
                    "USER", sender.userId(), "Peer transfer", locked);
            return finish(c, idemId, pr);
        });
    }

    // ------------------------------------------------------------------
    // 3. WITHDRAW: wallet -> CASH_OUT   (201 done, 202 held)
    // ------------------------------------------------------------------
    public IdemResult withdraw(String keyHeader, WithdrawRequest r) {
        UUID key = parseKey(keyHeader);
        requireBody(r);
        Long walletId = requireId(r.walletId(), "walletId");
        long amount = requireAmount(r.amountPaise());
        String hash = sha256("WITHDRAW|" + walletId + "|" + amount + "|" + noteText(r.note()));

        return inTransaction(c -> {
            long userId = requireOwner(c, walletId);

            Long idemId = idem.insertIfAbsent(c, userId, key, hash);
            if (idemId == null) return replayOf(c, userId, key, hash);

            long cashOut = repo.systemAccountId(c, "CASH_OUT");
            Map<Long, LockedAccount> locked = repo.lockAccounts(c, List.of(cashOut, walletId));
            LockedAccount wallet = requireWallet(locked, walletId);

            List<Leg> legs = List.of(
                    new Leg(walletId, "DEBIT", amount),
                    new Leg(cashOut, "CREDIT", amount));
            PostResult pr = post(c, "WITHDRAWAL", legs, amount, r.note(), wallet.userId(), null,
                    "USER", wallet.userId(), "Withdrawal", locked);
            return finish(c, idemId, pr);
        });
    }

    // ------------------------------------------------------------------
    // 4. REVERSE: a new transfer that undoes an old one
    // ------------------------------------------------------------------
    public IdemResult reverse(String keyHeader, long originalId) {
        UUID key = parseKey(keyHeader);
        String hash = sha256("REVERSE|" + originalId);

        return inTransaction(c -> {
            // The key belongs to the user who started the original transfer
            Initiator initiator = repo.initiatorOf(c, originalId);
            if (initiator == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
            }
            if (initiator.userId() == null) {
                throw unprocessable("CANNOT_REVERSE_REVERSAL", "A reversal cannot be reversed");
            }
            long userId = initiator.userId();

            Long idemId = idem.insertIfAbsent(c, userId, key, hash);
            if (idemId == null) return replayOf(c, userId, key, hash);

            TransferResponse reversal = doReverse(c, originalId);
            return finish(c, idemId, new PostResult(reversal, false));
        });
    }

    /** The reversal itself. Used by POST /transfers/{id}/reverse and by the admin REVERSE decision. */
    private TransferResponse doReverse(Connection c, long originalId) throws SQLException {
        // Lock the original row: two reversals of the same transfer cannot run together
        TransferResponse original = repo.lockTransfer(c, originalId);
        if (original == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        if ("REVERSAL".equals(original.transferType())) {
            throw unprocessable("CANNOT_REVERSE_REVERSAL", "A reversal cannot be reversed");
        }
        if (!"COMPLETED".equals(original.status())) {
            throw unprocessable("NOT_REVERSIBLE", "Only COMPLETED transfers can be reversed");
        }
        if (repo.reversalExists(c, originalId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVERSED",
                    "This transfer was already reversed");
        }

        // Flip every original entry: DEBIT becomes CREDIT and the other way round
        List<Leg> legs = new ArrayList<>();
        for (EntryView e : repo.findEntries(c, originalId)) {
            String flipped = e.direction().equals("DEBIT") ? "CREDIT" : "DEBIT";
            legs.add(new Leg(e.accountId(), flipped, e.amountPaise()));
        }

        Set<Long> ids = new TreeSet<>();
        for (Leg l : legs) ids.add(l.accountId());
        Map<Long, LockedAccount> locked = repo.lockAccounts(c, ids);

        // No admin user table exists, so the audit row is written as SYSTEM (no user id)
        return post(c, "REVERSAL", legs, original.amountPaise(),
                "Reversal of transfer #" + originalId, null, originalId,
                "SYSTEM", null, "Reversal of transfer #" + originalId, locked).transfer();
    }

    // ------------------------------------------------------------------
    // 5. GET one transfer with its entries and status history
    // ------------------------------------------------------------------
    public TransferDetailResponse getTransfer(long id) {
        TransferResponse t = repo.findTransfer(id);
        if (t == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        return new TransferDetailResponse(t, repo.findEntries(id), repo.findAudit(id));
    }

    // ------------------------------------------------------------------
    // 6. ADMIN: risk queue
    // ------------------------------------------------------------------
    public RiskQueueResponse riskQueue(Integer limitParam, Integer offsetParam) {
        int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
        int offset = offsetParam == null ? 0 : offsetParam;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "offset must be 0 or more");
        }
        var items = riskRepo.findQueue(limit, offset);
        return new RiskQueueResponse(limit, offset, items.size(), items);
    }

    // ------------------------------------------------------------------
    // 7. ADMIN: decision on a held or flagged transfer
    //    RELEASE : HELD -> money moves, transfer becomes COMPLETED
    //    REVERSE : HELD -> cancelled (FAILED, no money ever moved)
    //              COMPLETED -> a normal reversal transfer is created
    //    DISMISS : COMPLETED but flagged -> admin says it is fine
    // ------------------------------------------------------------------
    public DecisionResponse decide(long transferId, DecisionRequest r) {
        requireBody(r);
        String decision = r.decision() == null ? "" : r.decision().trim().toUpperCase();
        if (!decision.equals("RELEASE") && !decision.equals("REVERSE") && !decision.equals("DISMISS")) {
            throw unprocessable("INVALID_DECISION", "decision must be RELEASE, REVERSE or DISMISS");
        }

        return inTransaction(c -> {
            // who decided: a real user as ADMIN, or SYSTEM when no admin id is given
            String auditType = "SYSTEM";
            Long auditUser = null;
            if (r.adminUserId() != null) {
                if (!riskRepo.userExists(c, r.adminUserId())) {
                    throw unprocessable("ADMIN_NOT_FOUND", "adminUserId " + r.adminUserId() + " not found");
                }
                auditType = "ADMIN";
                auditUser = r.adminUserId();
            }
            String reason = "Admin " + decision + (r.reason() == null ? "" : ": " + r.reason());

            TransferResponse t = repo.lockTransfer(c, transferId);   // one decision at a time
            if (t == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
            }

            Long reversalId = null;

            switch (decision) {
                case "RELEASE" -> {
                    if (!"HELD".equals(t.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "NOT_HELD", "Only HELD transfers can be released");
                    }
                    releaseHeld(c, t, auditType, auditUser, reason);
                    riskRepo.closeOpenFlags(c, transferId, "RELEASED");
                }
                case "REVERSE" -> {
                    if ("HELD".equals(t.status())) {
                        repo.updateStatus(c, transferId, "FAILED");     // no money moved, nothing to undo
                        repo.insertAudit(c, transferId, "HELD", "FAILED", auditType, auditUser, reason);
                    } else if ("COMPLETED".equals(t.status())) {
                        reversalId = doReverse(c, transferId).transferId();
                    } else {
                        throw new ApiException(HttpStatus.CONFLICT, "NOT_REVERSIBLE",
                                "Transfer status " + t.status() + " cannot be reversed");
                    }
                    riskRepo.closeOpenFlags(c, transferId, "REVERSED");
                }
                default -> {   // DISMISS
                    if (!"COMPLETED".equals(t.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "NOT_COMPLETED",
                                "Held transfers need RELEASE or REVERSE");
                    }
                    if (riskRepo.closeOpenFlags(c, transferId, "DISMISSED") == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "NO_OPEN_FLAGS", "This transfer has no open flags");
                    }
                }
            }

            String status = repo.lockTransfer(c, transferId).status();
            return new DecisionResponse(transferId, decision, status, reversalId);
        });
    }

    /** Moves the money of a HELD transfer now. Balances are checked again under the lock. */
    private void releaseHeld(Connection c, TransferResponse t, String auditType, Long auditUser,
                             String reason) throws SQLException {
        TransferAccounts acc = repo.accountsOf(c, t.transferId());
        if (acc == null || acc.fromAccountId() == null || acc.toAccountId() == null) {
            throw new IllegalStateException("Held transfer " + t.transferId() + " has no accounts saved");
        }
        long amount = t.amountPaise();

        Map<Long, LockedAccount> locked = repo.lockAccounts(c, List.of(acc.fromAccountId(), acc.toAccountId()));
        LockedAccount from = locked.get(acc.fromAccountId());
        if ("USER_WALLET".equals(from.accountType()) && from.balancePaise() - amount < 0) {
            throw unprocessable("INSUFFICIENT_FUNDS",
                    "Wallet " + from.accountId() + " no longer has enough money to release this transfer");
        }

        repo.insertEntry(c, t.transferId(), acc.fromAccountId(), "DEBIT", amount);
        repo.insertEntry(c, t.transferId(), acc.toAccountId(), "CREDIT", amount);
        repo.applyDelta(c, acc.fromAccountId(), -amount);
        repo.applyDelta(c, acc.toAccountId(), amount);
        repo.updateStatus(c, t.transferId(), "COMPLETED");
        repo.insertAudit(c, t.transferId(), "HELD", "COMPLETED", auditType, auditUser, reason);
    }

    // ==================================================================
    // IDEMPOTENCY helpers
    // ==================================================================

    /** Saves the response on the key row. 201 = done, 202 = held for review. */
    private IdemResult finish(Connection c, long idemId, PostResult pr) throws SQLException {
        int code = pr.held() ? 202 : 201;
        return new IdemResult(code, idem.complete(c, idemId, code, pr.transfer()));
    }

    /** The key was seen before. Decide what to answer. */
    private IdemResult replayOf(Connection c, long userId, UUID key, String hash) throws SQLException {
        Existing e = idem.find(c, userId, key);
        if (e == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "A request with this key is still running. Try again shortly.");
        }
        if (!e.requestHash().equals(hash)) {
            throw unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request");
        }
        if ("COMPLETED".equals(e.status())) {
            return new IdemResult(e.responseCode(), e.responseBody());   // saved response, exactly
        }
        throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                "A request with this key is still running. Try again shortly.");
    }

    private UUID parseKey(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required");
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be a UUID");
        }
    }

    private String noteText(String note) {
        return note == null ? "<null>" : "note=" + note;
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private long requireOwner(Connection c, long walletId) throws SQLException {
        Long owner = repo.ownerOfWallet(c, walletId);
        if (owner == null) {
            throw unprocessable("WALLET_NOT_FOUND", "Wallet " + walletId + " not found");
        }
        return owner;
    }

    // ==================================================================
    // THE MONEY PATH. Accounts are already locked when we get here.
    // ==================================================================
    private PostResult post(Connection c, String type, List<Leg> legs, long amount,
                            String note, Long initiatedBy, Long originalId,
                            String auditType, Long auditUserId, String auditReason,
                            Map<Long, LockedAccount> locked) throws SQLException {

        // a) the books must balance: debits == credits
        long debits = 0, credits = 0;
        Long fromAccount = null, toAccount = null;
        Map<Long, Long> delta = new LinkedHashMap<>();   // net change per account
        for (Leg l : legs) {
            boolean credit = l.direction().equals("CREDIT");
            if (credit) {
                credits += l.amount();
                if (toAccount == null) toAccount = l.accountId();
            } else {
                debits += l.amount();
                if (fromAccount == null) fromAccount = l.accountId();
            }
            delta.merge(l.accountId(), credit ? l.amount() : -l.amount(), Long::sum);
        }
        if (debits != credits) {
            throw new IllegalStateException("Unbalanced transfer: debits " + debits + " credits " + credits);
        }

        // b) re-check balances WHILE HOLDING THE LOCK (system accounts may go negative)
        for (Map.Entry<Long, Long> e : delta.entrySet()) {
            LockedAccount a = locked.get(e.getKey());
            if ("USER_WALLET".equals(a.accountType()) && a.balancePaise() + e.getValue() < 0) {
                throw unprocessable("INSUFFICIENT_FUNDS",
                        "Wallet " + a.accountId() + " does not have enough money");
            }
        }

        // c) fraud rules, still inside the transaction, before anything is written
        List<RuleHit> hits = fraud.evaluate(c, type, fromAccount, amount);
        boolean hold = hits.stream().anyMatch(h -> "HOLD".equals(h.action()));
        String status = hold ? "HELD" : "COMPLETED";

        // d) write. A HELD transfer saves the transfer row only: no entries, no balance change.
        TransferResponse transfer = repo.insertTransfer(c, type, status, amount, note,
                initiatedBy, originalId, fromAccount, toAccount);
        if (!hold) {
            for (Leg l : legs) {
                repo.insertEntry(c, transfer.transferId(), l.accountId(), l.direction(), l.amount());
            }
            for (Map.Entry<Long, Long> e : delta.entrySet()) {
                repo.applyDelta(c, e.getKey(), e.getValue());
            }
        }
        String reason = hold ? "Held by rule " + hits.stream().map(RuleHit::ruleName).toList() : auditReason;
        repo.insertAudit(c, transfer.transferId(), null, status, auditType, auditUserId, reason);
        for (RuleHit h : hits) {
            riskRepo.insertFlag(c, transfer.transferId(), h.ruleId());
        }

        return new PostResult(transfer, hold);   // the commit happens in inTransaction()
    }

    // ==================================================================
    // Transaction wrapper: BEGIN ... COMMIT / ROLLBACK
    // ==================================================================
    private <T> T inTransaction(TxWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);                                          // BEGIN
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T result = work.run(conn);
                conn.commit();                                                  // COMMIT
                return result;
            } catch (SQLException e) {
                conn.rollback();                                                // ROLLBACK
                throw mapSql(e);
            } catch (RuntimeException e) {
                conn.rollback();                                                // ROLLBACK
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error", e);
        }
    }

    private RuntimeException mapSql(SQLException e) {
        if ("23505".equals(e.getSQLState())) {   // unique rule broken
            return new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "This request conflicts with an existing record");
        }
        if ("23514".equals(e.getSQLState())) {   // check rule broken
            return unprocessable("RULE_VIOLATION", "The database rejected this change");
        }
        return new RuntimeException("Database error", e);
    }

    // ------------------------------------------------------------------ small checks
    private void requireBody(Object body) {
        if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is required");
    }

    private Long requireId(Long id, String name) {
        if (id == null) throw unprocessable("MISSING_FIELD", name + " is required");
        return id;
    }

    private long requireAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw unprocessable("INVALID_AMOUNT", "amountPaise must be a whole number greater than 0");
        }
        return amount;
    }

    private LockedAccount requireWallet(Map<Long, LockedAccount> locked, long id) {
        LockedAccount a = locked.get(id);
        if (a == null || !"USER_WALLET".equals(a.accountType())) {
            throw unprocessable("WALLET_NOT_FOUND", "Wallet " + id + " not found");
        }
        return a;
    }

    private ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
