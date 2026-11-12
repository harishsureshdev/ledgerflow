package com.ledgerflow.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    @DisplayName("Create valid money instance in cents")
    void createMoney() {
        Money money = Money.of(1050L, "USD");
        assertThat(money.amount()).isEqualTo(1050L);
        assertThat(money.currency()).isEqualTo("USD");
        assertThat(money.isPositive()).isTrue();
        assertThat(money.isZero()).isFalse();
        assertThat(money.isNegative()).isFalse();
    }

    @Test
    @DisplayName("Arithmetic operations with same currency")
    void arithmeticSameCurrency() {
        Money m1 = Money.of(500L, "USD");
        Money m2 = Money.of(250L, "USD");

        assertThat(m1.plus(m2)).isEqualTo(Money.of(750L, "USD"));
        assertThat(m1.minus(m2)).isEqualTo(Money.of(250L, "USD"));
        assertThat(m2.minus(m1)).isEqualTo(Money.of(-250L, "USD"));
        assertThat(m1.negate()).isEqualTo(Money.of(-500L, "USD"));
    }

    @Test
    @DisplayName("Cannot combine different currencies")
    void currencyMismatch() {
        Money usd = Money.of(100L, "USD");
        Money eur = Money.of(100L, "EUR");

        assertThatThrownBy(() -> usd.plus(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("Invalid currency code throws exception")
    void invalidCurrencyCode() {
        assertThatThrownBy(() -> Money.of(100L, "INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Zero money representation")
    void zeroMoney() {
        Money zero = Money.zero("USD");
        assertThat(zero.isZero()).isTrue();
        assertThat(zero.amount()).isZero();
    }
}
