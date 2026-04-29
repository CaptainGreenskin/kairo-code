package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentLedgerTest {

    private PaymentLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new PaymentLedger();
    }

    @Test
    void recordStoresPayment() {
        ledger.record("o1", "p1", 10.0);

        assertThat(ledger.getPayment("o1")).hasValue(10.0);
    }

    @Test
    void recordUpdatesProductTotal() {
        ledger.record("o1", "p1", 10.0);
        ledger.record("o2", "p1", 20.0);

        assertThat(ledger.totalByProduct("p1")).isEqualTo(30.0);
    }

    @Test
    void missingPaymentReturnsEmpty() {
        assertThat(ledger.getPayment("nonexistent")).isEmpty();
    }

    @Test
    void paymentCountMatchesRecords() {
        ledger.record("o1", "p1", 10.0);
        ledger.record("o2", "p2", 20.0);
        ledger.record("o3", "p1", 15.0);

        assertThat(ledger.paymentCount()).isEqualTo(3);
    }

    @Test
    void grandTotalIsSumOfAllPayments() {
        ledger.record("o1", "p1", 10.0);
        ledger.record("o2", "p2", 20.0);
        ledger.record("o3", "p1", 15.0);

        assertThat(ledger.grandTotal()).isEqualTo(45.0);
    }

    @Test
    void totalByProductReturnsZeroForUnknown() {
        assertThat(ledger.totalByProduct("unknown")).isEqualTo(0.0);
    }

    @Test
    void snapshotReturnsImmutablePayments() {
        ledger.record("o1", "p1", 10.0);
        var snap = ledger.paymentsSnapshot();

        assertThatThrownBy(() -> ((java.util.Map<String, Double>) snap).put("o2", 20.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void multipleProductsTrackedIndependently() {
        ledger.record("o1", "p1", 10.0);
        ledger.record("o2", "p2", 20.0);
        ledger.record("o3", "p1", 5.0);

        assertThat(ledger.totalByProduct("p1")).isEqualTo(15.0);
        assertThat(ledger.totalByProduct("p2")).isEqualTo(20.0);
    }
}
