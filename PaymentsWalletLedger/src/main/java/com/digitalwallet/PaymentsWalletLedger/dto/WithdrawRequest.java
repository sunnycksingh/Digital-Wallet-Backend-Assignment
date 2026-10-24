package com.digitalwallet.PaymentsWalletLedger.dto;

public record WithdrawRequest(Long walletId, Long amountPaise, String note) {}
