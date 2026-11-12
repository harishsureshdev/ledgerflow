package com.ledgerflow.payments.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    @ParameterizedTest(name = "Allowed transition from {0} to {1}")
    @CsvSource({
            "INITIATED, AUTHORIZED",
            "INITIATED, FAILED",
            "AUTHORIZED, CAPTURED",
            "AUTHORIZED, FAILED",
            "CAPTURED, SETTLED",
            "CAPTURED, REVERSED",
            "SETTLED, REVERSED"
    })
    void allowedTransitionsSucceed(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.isValidTransition(from, to)).isTrue();
        PaymentStateMachine.validateTransition(from, to);
    }

    @ParameterizedTest(name = "Illegal transition from {0} to {1}")
    @CsvSource({
            // From INITIATED
            "INITIATED, INITIATED",
            "INITIATED, CAPTURED",
            "INITIATED, SETTLED",
            "INITIATED, REVERSED",

            // From AUTHORIZED
            "AUTHORIZED, INITIATED",
            "AUTHORIZED, AUTHORIZED",
            "AUTHORIZED, SETTLED",
            "AUTHORIZED, REVERSED",

            // From CAPTURED
            "CAPTURED, INITIATED",
            "CAPTURED, AUTHORIZED",
            "CAPTURED, CAPTURED",
            "CAPTURED, FAILED",

            // From SETTLED
            "SETTLED, INITIATED",
            "SETTLED, AUTHORIZED",
            "SETTLED, CAPTURED",
            "SETTLED, SETTLED",
            "SETTLED, FAILED",

            // From FAILED (terminal state)
            "FAILED, INITIATED",
            "FAILED, AUTHORIZED",
            "FAILED, CAPTURED",
            "FAILED, SETTLED",
            "FAILED, FAILED",
            "FAILED, REVERSED",

            // From REVERSED (terminal state)
            "REVERSED, INITIATED",
            "REVERSED, AUTHORIZED",
            "REVERSED, CAPTURED",
            "REVERSED, SETTLED",
            "REVERSED, FAILED",
            "REVERSED, REVERSED"
    })
    void illegalTransitionsFail(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.isValidTransition(from, to)).isFalse();
        assertThatThrownBy(() -> PaymentStateMachine.validateTransition(from, to))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal payment state transition from " + from + " to " + to);
    }

    @Test
    @DisplayName("Null states are rejected")
    void nullStatesRejected() {
        assertThatThrownBy(() -> PaymentStateMachine.validateTransition(null, PaymentStatus.AUTHORIZED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentStateMachine.validateTransition(PaymentStatus.INITIATED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
