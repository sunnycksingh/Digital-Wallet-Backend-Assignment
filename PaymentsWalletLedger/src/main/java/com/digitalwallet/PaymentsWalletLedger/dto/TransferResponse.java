package com.digitalwallet.PaymentsWalletLedger.dto;

import java.time.OffsetDateTime;

public record TransferResponse(
        long transferId,
        String transferType,
        String status,
        long amountPaise,
        String amountRupees,
        Long originalTransferId,
        String note,
        OffsetDateTime createdAt) {}
