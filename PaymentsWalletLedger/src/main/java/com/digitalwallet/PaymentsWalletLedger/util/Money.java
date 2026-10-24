package com.digitalwallet.PaymentsWalletLedger.util;

public final class Money {
    private Money() {}

    public static String toRupees(long paise) {
        return (paise / 100) + "." + String.format("%02d", paise % 100);
    }
}
