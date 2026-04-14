package com.polysign.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trivial serialization-style test confirming the new {@code priceAtAlert}
 * field on {@link Alert} round-trips through getter/setter as expected.
 */
class AlertPriceAtAlertTest {

    @Test
    void priceAtAlert_roundTrips() {
        Alert alert = new Alert();
        assertThat(alert.getPriceAtAlert()).isNull();

        alert.setPriceAtAlert(new BigDecimal("0.482"));
        assertThat(alert.getPriceAtAlert()).isEqualByComparingTo("0.482");
    }

    @Test
    void priceAtAlert_doesNotAffectOtherFields() {
        Alert alert = new Alert();
        alert.setAlertId("test-id");
        alert.setType("price_movement");
        alert.setPriceAtAlert(new BigDecimal("0.75"));

        assertThat(alert.getAlertId()).isEqualTo("test-id");
        assertThat(alert.getType()).isEqualTo("price_movement");
        assertThat(alert.getPriceAtAlert()).isEqualByComparingTo("0.75");
    }
}
