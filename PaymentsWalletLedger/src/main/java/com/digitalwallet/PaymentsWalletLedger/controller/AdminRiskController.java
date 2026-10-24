package com.digitalwallet.PaymentsWalletLedger.controller;

import com.digitalwallet.PaymentsWalletLedger.dto.RiskDtos.DecisionRequest;
import com.digitalwallet.PaymentsWalletLedger.dto.RiskDtos.DecisionResponse;
import com.digitalwallet.PaymentsWalletLedger.dto.RiskDtos.RiskQueueResponse;
import com.digitalwallet.PaymentsWalletLedger.service.TransferService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/risk")
public class AdminRiskController {

    private final TransferService service;

    public AdminRiskController(TransferService service) {
        this.service = service;
    }

    /** Open flags, newest first. Held transfers and flagged transfers both show up here. */
    @GetMapping("/queue")
    public RiskQueueResponse queue(@RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) Integer offset) {
        return service.riskQueue(limit, offset);
    }

    /** Body: {"decision":"RELEASE" | "REVERSE" | "DISMISS", "reason":"...", "adminUserId":1} */
    @PostMapping("/{transferId}/decision")
    public DecisionResponse decide(@PathVariable long transferId,
                                   @RequestBody(required = false) DecisionRequest request) {
        return service.decide(transferId, request);
    }
}
