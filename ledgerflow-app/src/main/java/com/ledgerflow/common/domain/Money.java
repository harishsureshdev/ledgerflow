package com.ledgerflow.common.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * Value object representing an amount of money in minor units (e.g. cents).
 * Floating point types are strictly forbidden for currency amounts to avoid rounding errors.
 */
public record Money(long amount, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "Currency must not be null");
        String normalizedCurrency = currency.trim().toUpperCase();
        try {
            Currency.getInstance(normalizedCurrency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid ISO-4217 currency code: " + currency, e);
        }
        currency = normalizedCurrency;
    }

    public static Money of(long amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money ofCents(long amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.addExact(this.amount, other.amount), this.currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.subtractExact(this.amount, other.amount), this.currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(this.amount), this.currency);
    }

    public boolean isZero() {
        return this.amount == 0;
    }

    public boolean isPositive() {
        return this.amount > 0;
    }

    public boolean isNegative() {
        return this.amount < 0;
    }

    public boolean hasSameCurrency(Money other) {
        return other != null && this.currency.equalsIgnoreCase(other.currency);
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "Other money must not be null");
        if (!hasSameCurrency(other)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: cannot operate on " + this.currency + " and " + other.currency);
        }
    }

    @Override
    public int compareTo(Money o) {
        assertSameCurrency(o);
        return Long.compare(this.amount, o.amount);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
