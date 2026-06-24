package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountResponse;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/escrow-accounts")
public class EscrowAccountController {

    private final EscrowAccountService service;

    public EscrowAccountController(EscrowAccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EscrowAccountResponse> create(@Valid @RequestBody EscrowAccountRequest request) {
        EscrowAccountResponse body = EscrowAccountResponse.from(service.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public EscrowAccountResponse get(@PathVariable String id) {
        return EscrowAccountResponse.from(service.get(id));
    }

    @GetMapping
    public List<EscrowAccountResponse> list() {
        return service.list().stream().map(EscrowAccountResponse::from).toList();
    }
}
