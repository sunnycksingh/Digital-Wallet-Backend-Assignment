package com.digitalwallet.PaymentsWalletLedger.dto;

public record TransferRequest(Long fromWalletId, Long toWalletId, Long amountPaise, String note) {}
