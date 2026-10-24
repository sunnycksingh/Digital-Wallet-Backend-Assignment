package com.digitalwallet.PaymentsWalletLedger;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentsWalletLedgerApplication {

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
		SpringApplication.run(PaymentsWalletLedgerApplication.class, args);
		System.out.println("Hello Dia welcome");
	}

}
