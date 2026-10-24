package com.digitalwallet.PaymentsWalletLedger.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.digitalwallet.PaymentsWalletLedger.dto.CreateWalletRequest;
import com.digitalwallet.PaymentsWalletLedger.model.Account;
import com.digitalwallet.PaymentsWalletLedger.service.WalletService;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/wallets")
public class WalletController {

    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Account> create(@PathVariable long userId,
                                          @RequestBody CreateWalletRequest request) {
        Account created = service.createWallet(userId, request.walletType());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<Account> list(@PathVariable long userId) {
        return service.listWallets(userId);
    }
}