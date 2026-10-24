package com.digitalwallet.PaymentsWalletLedger.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class ReconciliationDtos {
    private ReconciliationDtos() {}

    public record RunView(long runId, OffsetDateTime startedAt, OffsetDateTime endedAt,
                          String status, long problemCount) {}

    public record IssueView(long issueId, String checkType, Long transferId, Long accountId,
                            Long ledgerEntryId, Long expectedValue, Long actualValue,
                            String description) {}

    public record RunReport(RunView run, List<IssueView> issues) {}
}
