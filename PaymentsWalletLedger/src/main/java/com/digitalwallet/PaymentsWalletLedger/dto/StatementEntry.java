package com.digitalwallet.PaymentsWalletLedger.dto;

import java.time.OffsetDateTime;

public record StatementEntry(
        long entryId,
        long transferId,
        String transferType,
        String status,
        String direction,
        long amountPaise,
        String amountRupees,
        Long counterpartyAccountId,   // Long: can be empty
        String note,
        OffsetDateTime createdAt) {}