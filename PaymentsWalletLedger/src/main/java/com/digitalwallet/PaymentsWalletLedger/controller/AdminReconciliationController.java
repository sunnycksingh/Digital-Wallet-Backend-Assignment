package com.digitalwallet.PaymentsWalletLedger.controller;

import com.digitalwallet.PaymentsWalletLedger.dto.ReconciliationDtos.RunReport;
import com.digitalwallet.PaymentsWalletLedger.dto.ReconciliationDtos.RunView;
import com.digitalwallet.PaymentsWalletLedger.service.ReconciliationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/reconciliation")
public class AdminReconciliationController {

    private final ReconciliationService service;

    public AdminReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** Manual run. 202 = accepted. The run is finished by the time the reply arrives. */
    @PostMapping("/run")
    public ResponseEntity<RunView> run() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.run());
    }

    @GetMapping("/runs/{id}")
    public RunReport report(@PathVariable long id) {
        return service.getReport(id);
    }
}
