package com.digitalwallet.PaymentsWalletLedger.dto;

import java.util.List;

public record StatementResponse(
        long walletId,
        int limit,
        int offset,
        int count,
        List<StatementEntry> entries) {}

