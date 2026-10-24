package com.digitalwallet.PaymentsWalletLedger.controller;

import com.digitalwallet.PaymentsWalletLedger.dto.*;
import com.digitalwallet.PaymentsWalletLedger.service.TransferService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private static final String KEY = "Idempotency-Key";

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/topup")
    public ResponseEntity<String> topUp(
            @RequestHeader(value = KEY, required = false) String key,
            @RequestBody(required = false) TopUpRequest request) {
        return respond(service.topUp(key, request));
    }

    @PostMapping
    public ResponseEntity<String> send(
            @RequestHeader(value = KEY, required = false) String key,
            @RequestBody(required = false) TransferRequest request) {
        return respond(service.send(key, request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<String> withdraw(
            @RequestHeader(value = KEY, required = false) String key,
            @RequestBody(required = false) WithdrawRequest request) {
        return respond(service.withdraw(key, request));
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<String> reverse(
            @RequestHeader(value = KEY, required = false) String key,
            @PathVariable long id) {
        return respond(service.reverse(key, id));
    }

    @GetMapping("/{id}")
    public TransferDetailResponse get(@PathVariable long id) {
        return service.getTransfer(id);
    }

    /** The body is already JSON text (saved on the key row), so it is sent back as it is. */
    private ResponseEntity<String> respond(IdemResult r) {
        return ResponseEntity.status(r.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(r.body());
    }
}
