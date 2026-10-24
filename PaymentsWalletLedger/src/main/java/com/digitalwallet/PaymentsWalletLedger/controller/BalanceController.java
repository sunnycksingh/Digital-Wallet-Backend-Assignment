package com.digitalwallet.PaymentsWalletLedger.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digitalwallet.PaymentsWalletLedger.dto.BalanceResponse;
import com.digitalwallet.PaymentsWalletLedger.service.WalletService;

@RestController
@RequestMapping("/wallets")
public class BalanceController {

    private final WalletService service;

    public BalanceController(WalletService service) {
        this.service = service;
    }

    @GetMapping("/{walletId}/balance")
    public BalanceResponse balance(@PathVariable long walletId) {
        return service.getBalance(walletId);
    }
}