package com.digitalwallet.PaymentsWalletLedger.dto;

public record BalanceResponse(
        long walletId,
        long balancePaise,
        String balanceRupees,
        String currency) {}