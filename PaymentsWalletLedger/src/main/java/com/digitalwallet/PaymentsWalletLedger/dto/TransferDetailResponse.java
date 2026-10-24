package com.digitalwallet.PaymentsWalletLedger.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TransferDetailResponse(
        TransferResponse transfer,
        List<EntryView> entries,
        List<AuditView> statusHistory) {

    public record EntryView(long ledgerEntryId, long accountId, String direction,
                            long amountPaise, OffsetDateTime createdAt) {}

    public record AuditView(String oldStatus, String newStatus, String changedByType,
                            Long changedByUserId, String reason, OffsetDateTime createdAt) {}
}
