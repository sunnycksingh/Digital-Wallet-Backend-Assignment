package com.digitalwallet.PaymentsWalletLedger.service;

import com.digitalwallet.PaymentsWalletLedger.repository.RiskRepository;
import com.digitalwallet.PaymentsWalletLedger.repository.RiskRepository.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Runs the fraud rules INSIDE the transfer transaction, before commit. Rules make every decision. */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    public record RuleHit(long ruleId, String ruleName, String action) {}

    private final RiskRepository repo;

    public FraudService(RiskRepository repo) {
        this.repo = repo;
    }

    public List<RuleHit> evaluate(Connection c, String transferType, long fromAccountId, long amount)
            throws SQLException {

        // Rules only watch money leaving a wallet. Top-ups and reversals are not checked.
        if (!transferType.equals("PEER_TRANSFER") && !transferType.equals("WITHDRAWAL")) {
            return List.of();
        }

        long started = System.nanoTime();
        List<RuleHit> hits = new ArrayList<>();
        for (Rule r : repo.loadActiveRules(c)) {
            boolean fired = switch (r.ruleType()) {
                case "HIGH_AMOUNT" -> r.threshold() != null && amount >= r.threshold();
                case "VELOCITY" -> r.threshold() != null
                        && repo.countRecentDebits(c, fromAccountId, r.windowMinutes()) >= r.threshold();
                default -> false;
            };
            if (fired) hits.add(new RuleHit(r.ruleId(), r.ruleName(), r.action()));
        }
        log.debug("Fraud rules took {} microseconds, {} rule(s) fired",
                (System.nanoTime() - started) / 1000, hits.size());
        return hits;
    }
}
