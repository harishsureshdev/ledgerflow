package com.ledgerflow.payments.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreatePaymentRequest(
        @NotBlank(message = "customerId is required")
        String customerId,

        @NotNull(message = "amount is required")
        @Min(value = 1, message = "amount must be greater than zero")
        Long amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a valid 3-letter ISO-4217 code")
        String currency,

        @NotBlank(message = "paymentToken is required")
        @Pattern(regexp = "^tok_[a-zA-Z0-9_]+$", message = "paymentToken must be a tokenized reference starting with 'tok_'")
        String paymentToken
) {
}
