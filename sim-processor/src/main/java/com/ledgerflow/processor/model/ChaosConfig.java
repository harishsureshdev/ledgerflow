package com.ledgerflow.processor.model;

public record ChaosConfig(
        long latencyMs,
        double failureRate, // 0.0 to 1.0
        double duplicateRate // 0.0 to 1.0
) {
    public static ChaosConfig normal() {
        return new ChaosConfig(0, 0.0, 0.0);
    }
}
