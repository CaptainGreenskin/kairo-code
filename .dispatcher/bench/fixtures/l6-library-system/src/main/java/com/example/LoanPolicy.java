package com.example;

import java.util.Map;

/**
 * Library lending policy.
 * Bugs:
 * 1. getMaxLoans returns swapped values (BASIC=5, PREMIUM=3 — should be reversed)
 * 2. calculateLateFee uses integer division, losing precision
 */
public class LoanPolicy {
    private final int loanDurationDays;
    private final long dailyFee; // in cents

    // Bug: BASIC gets more loans than PREMIUM (swapped)
    private static final Map<MembershipType, Integer> MAX_LOANS = Map.of(
            MembershipType.BASIC, 5,
            MembershipType.PREMIUM, 3
    );

    public LoanPolicy(int loanDurationDays, long dailyFee) {
        if (loanDurationDays <= 0) throw new IllegalArgumentException("loanDurationDays must be positive");
        if (dailyFee < 0) throw new IllegalArgumentException("dailyFee must be non-negative");
        this.loanDurationDays = loanDurationDays;
        this.dailyFee = dailyFee;
    }

    public int getLoanDurationDays() { return loanDurationDays; }
    public long getDailyFee() { return dailyFee; }

    /**
     * Bug: MAX_LOANS map has BASIC and PREMIUM swapped.
     */
    public int getMaxLoans(MembershipType type) {
        return MAX_LOANS.getOrDefault(type, 0);
    }

    /**
     * Bug: integer division truncates the result.
     * e.g. daysLate=3, dailyFee=50 → 3*50/100 = 1 (should be 1.50)
     */
    public long calculateLateFee(long daysLate) {
        if (daysLate <= 0) return 0;
        return daysLate * dailyFee / 100;
    }
}
