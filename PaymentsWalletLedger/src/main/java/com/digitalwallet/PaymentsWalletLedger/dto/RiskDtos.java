package com.digitalwallet.PaymentsWalletLedger.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class RiskDtos {
    private RiskDtos() {}

    /** decision: RELEASE, REVERSE or DISMISS. adminUserId is optional (no admin table exists). */
    public record DecisionRequest(String decision, String reason, Long adminUserId) {}

    public record DecisionResponse(long transferId, String decision, String transferStatus,
                                   Long reversalTransferId) {}

    public record RiskQueueItem(long riskFlagId, long transferId, String transferType,
                                String transferStatus, long amountPaise, String amountRupees,
                                Long initiatedByUserId, String ruleName, String action,
                                String flagStatus, String llmLabel, String llmExplanation,
                                OffsetDateTime createdAt) {}

    public record RiskQueueResponse(int limit, int offset, int count, List<RiskQueueItem> items) {}
}
