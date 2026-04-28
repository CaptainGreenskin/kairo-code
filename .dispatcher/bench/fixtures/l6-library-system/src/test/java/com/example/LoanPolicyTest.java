package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LoanPolicy class.
 * Tests getMaxLoans per membership type and calculateLateFee.
 */
class LoanPolicyTest {

    private final LoanPolicy policy = new LoanPolicy(14, 50); // 14 days, 50 cents/day

    @Test
    void getLoanDurationDays() {
        assertEquals(14, policy.getLoanDurationDays());
    }

    @Test
    void getDailyFee() {
        assertEquals(50, policy.getDailyFee());
    }

    @Test
    void basicMemberMaxLoans() {
        // BASIC should have fewer loans than PREMIUM
        assertEquals(3, policy.getMaxLoans(MembershipType.BASIC));
    }

    @Test
    void premiumMemberMaxLoans() {
        // PREMIUM should have more loans than BASIC
        assertEquals(5, policy.getMaxLoans(MembershipType.PREMIUM));
    }

    @Test
    void premiumHasMoreLoansThanBasic() {
        int basicMax = policy.getMaxLoans(MembershipType.BASIC);
        int premiumMax = policy.getMaxLoans(MembershipType.PREMIUM);
        assertTrue(premiumMax > basicMax, "PREMIUM should have more max loans than BASIC");
    }

    @Test
    void calculateLateFeeZeroDays() {
        assertEquals(0, policy.calculateLateFee(0));
    }

    @Test
    void calculateLateFeeNegativeDays() {
        assertEquals(0, policy.calculateLateFee(-5));
    }

    @Test
    void calculateLateFeeOneDay() {
        // 1 day * 50 cents / 100 = 0.50 → rounds to 1 cent
        assertEquals(1, policy.calculateLateFee(1));
    }

    @Test
    void calculateLateFeeThreeDays() {
        // 3 days * 50 cents / 100 = 1.50 → rounds to 2 cents
        assertEquals(2, policy.calculateLateFee(3));
    }

    @Test
    void calculateLateFeeTenDays() {
        // 10 days * 50 cents / 100 = 5.00 → should be 5
        assertEquals(5, policy.calculateLateFee(10));
    }

    @Test
    void calculateLateFeePrecisionNotLost() {
        // With integer division: 3*50/100 = 1 (correct) but 7*50/100 = 3 (should be 3.5 → 3 or 4)
        // The bug causes truncation: we test that fractional cents are handled properly
        // 1 day * 50 / 100.0 = 0.5 → rounded = 1 (proper rounding)
        // With bug: 1*50/100 = 0 (truncated, should be 1)
        assertEquals(1, policy.calculateLateFee(1), "1 day late fee should round to 1 cent, not truncate to 0");
    }
}
