package com.ledgerflow.processor.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RefundRequest(
        @NotBlank String processorTransactionId,
        @NotNull @Min(1) Long amount,
        @NotBlank String currency,
        @NotBlank String idempotencyKey
) {
}
