package com.ledgerflow.payments.controller;

import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.dto.PaymentResponse;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.payments.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;

    public PaymentController(PaymentService paymentService, PaymentRepository paymentRepository) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Merchant-Id", defaultValue = "merch_default") String merchantId,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        PaymentService.PaymentResult result = paymentService.processPayment(merchantId, idempotencyKey, request);

        if (result.replayed()) {
            return ResponseEntity.status(result.statusCode()).body(result.response());
        }

        return ResponseEntity
                .created(URI.create("/api/v1/payments/" + result.response().id()))
                .body(result.response());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<java.util.List<PaymentResponse>> getRecentPayments(Authentication authentication) {
        java.util.List<Payment> payments;
        if (authentication != null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"))) {
            payments = paymentRepository.findByCustomerIdOrderByCreatedAtDesc(authentication.getName());
        } else {
            payments = paymentRepository.findAllByOrderByCreatedAtDesc();
        }
        return ResponseEntity.ok(payments.stream().map(PaymentResponse::from).toList());
    }

    /**
     * RBAC Rule:
     * Customers can strictly only view their OWN payments.
     * Ops agents and Finance Admins can view any payment.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable("id") UUID id,
            Authentication authentication
    ) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (authentication != null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"))) {
            if (!payment.getCustomerId().equals(authentication.getName())) {
                throw new AccessDeniedException("Access Denied: Customers can only access their own payments");
            }
        }

        return ResponseEntity.ok(PaymentResponse.from(payment));
    }
}
