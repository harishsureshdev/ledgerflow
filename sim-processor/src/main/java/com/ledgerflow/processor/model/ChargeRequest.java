package com.ledgerflow.processor.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChargeRequest(
        @NotBlank String token,
        @NotNull @Min(1) Long amount,
        @NotBlank String currency,
        @NotBlank String idempotencyKey
) {
}
