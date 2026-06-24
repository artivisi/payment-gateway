package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.ConsumerResponse;
import com.artivisi.paymentgateway.service.ConsumerService;
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
@RequestMapping("/api/consumers")
public class ConsumerController {

    private final ConsumerService service;

    public ConsumerController(ConsumerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ConsumerResponse> create(@Valid @RequestBody ConsumerRequest request) {
        ConsumerResponse body = ConsumerResponse.from(service.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public ConsumerResponse get(@PathVariable String id) {
        return ConsumerResponse.from(service.get(id));
    }

    @GetMapping
    public List<ConsumerResponse> list() {
        return service.list().stream().map(ConsumerResponse::from).toList();
    }
}
