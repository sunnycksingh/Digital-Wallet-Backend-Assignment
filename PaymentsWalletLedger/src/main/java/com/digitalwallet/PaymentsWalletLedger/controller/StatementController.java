package com.digitalwallet.PaymentsWalletLedger.controller;

import com.digitalwallet.PaymentsWalletLedger.dto.StatementResponse;
import com.digitalwallet.PaymentsWalletLedger.service.StatementService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallets")
public class StatementController {

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    @GetMapping("/{walletId}/statement")
    public StatementResponse statement(@PathVariable long walletId,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) Integer offset) {
        return service.getStatement(walletId, limit, offset);
    }
}