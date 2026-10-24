package com.digitalwallet.PaymentsWalletLedger.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.digitalwallet.PaymentsWalletLedger.dto.CreateUserRequest;
import com.digitalwallet.PaymentsWalletLedger.model.User;
import com.digitalwallet.PaymentsWalletLedger.repository.UserRepository;

import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserRepository repo;

    public UserController(UserRepository repo) {
        this.repo = repo;
    }

//    @PostMapping
//    public Map<String, Object> create(@RequestBody CreateUserRequest request) {
//        long id = repo.create(request);
//        return Map.of("id", id);
//    }
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateUserRequest request) {
        long id = repo.create(request);
        return ResponseEntity.status(201).body(Map.of("id", id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> get(@PathVariable long id) {
        User user = repo.findById(id);
        return user == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(user);
    }
}
