package com.digitalwallet.PaymentsWalletLedger.dto;

public record TopUpRequest(Long walletId, Long amountPaise, String note) {}
