package com.digitalwallet.PaymentsWalletLedger.model;

public record Account(
        long accountId,
        Long userId,               // Long (not long) because it is NULL for system accounts
        String accountType,
        String walletType,
        String systemAccountCode,
        long balancePaise,
        String currency) {
	
}
