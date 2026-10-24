package com.digitalwallet.PaymentsWalletLedger.dto;

/** The final HTTP status and the exact JSON text to send back (first call and every replay). */
public record IdemResult(int status, String body) {}
