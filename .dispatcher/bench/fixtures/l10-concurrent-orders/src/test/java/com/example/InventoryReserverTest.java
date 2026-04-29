package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryReserverTest {

    private InventoryReserver reserver;

    @BeforeEach
    void setUp() {
        reserver = new InventoryReserver();
        reserver.initProduct("p1", 100);
        reserver.initProduct("p2", 50);
    }

    @Test
    void reserveDecreasesAvailable() {
        boolean ok = reserver.reserve("p1", 10);

        assertThat(ok).isTrue();
        assertThat(reserver.available("p1")).isEqualTo(90);
    }

    @Test
    void reserveIncreasesReserved() {
        reserver.reserve("p1", 10);

        assertThat(reserver.reserved("p1")).isEqualTo(10);
    }

    @Test
    void reserveFailsWhenInsufficient() {
        boolean ok = reserver.reserve("p1", 200);

        assertThat(ok).isFalse();
        assertThat(reserver.available("p1")).isEqualTo(100);
    }

    @Test
    void releaseRestoresAvailable() {
        reserver.reserve("p1", 10);
        reserver.release("p1", 5);

        assertThat(reserver.available("p1")).isEqualTo(95);
        assertThat(reserver.reserved("p1")).isEqualTo(5);
    }

    @Test
    void totalRemainsConstant() {
        reserver.reserve("p1", 30);
        reserver.reserve("p1", 20);

        assertThat(reserver.total("p1")).isEqualTo(100);
    }

    @Test
    void unknownProductReturnsZero() {
        assertThat(reserver.available("unknown")).isZero();
        assertThat(reserver.reserved("unknown")).isZero();
    }

    @Test
    void multipleReservationsAccumulate() {
        reserver.reserve("p1", 10);
        reserver.reserve("p1", 20);
        reserver.reserve("p1", 5);

        assertThat(reserver.reserved("p1")).isEqualTo(35);
        assertThat(reserver.available("p1")).isEqualTo(65);
    }

    @Test
    void fullReleaseRestoresInitialState() {
        reserver.reserve("p2", 50);
        reserver.release("p2", 50);

        assertThat(reserver.available("p2")).isEqualTo(50);
        assertThat(reserver.reserved("p2")).isZero();
    }
}
