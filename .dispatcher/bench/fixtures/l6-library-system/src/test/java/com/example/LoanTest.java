package com.example;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Loan class.
 * Tests isOverdue, returnBook, daysOverdue behavior.
 */
class LoanTest {

    @Test
    void newLoanIsActive() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        assertEquals(LoanStatus.ACTIVE, loan.getStatus());
    }

    @Test
    void newLoanIsNotOverdueBeforeDueDate() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        assertFalse(loan.isOverdue(LocalDate.of(2025, 1, 10)));
    }

    @Test
    void newLoanIsNotOverdueOnDueDate() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        assertFalse(loan.isOverdue(LocalDate.of(2025, 1, 15)));
    }

    @Test
    void newLoanIsOverdueAfterDueDate() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        assertTrue(loan.isOverdue(LocalDate.of(2025, 1, 20)));
    }

    @Test
    void returnedLoanIsNotOverdue() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        loan.returnBook(LocalDate.of(2025, 1, 14));
        // After returning, even if today is past due date, it should NOT be overdue
        assertFalse(loan.isOverdue(LocalDate.of(2025, 1, 20)));
    }

    @Test
    void returnedLoanHasReturnedStatus() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        loan.returnBook(LocalDate.of(2025, 1, 14));
        assertEquals(LoanStatus.RETURNED, loan.getStatus());
    }

    @Test
    void returnedLoanHasReturnedAtDate() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        LocalDate returnDate = LocalDate.of(2025, 1, 14);
        loan.returnBook(returnDate);
        assertEquals(returnDate, loan.getReturnedAt());
    }

    @Test
    void daysOverdueReturnsZeroWhenNotOverdue() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        assertEquals(0, loan.daysOverdue(LocalDate.of(2025, 1, 10)));
    }

    @Test
    void daysOverdueReturnsCorrectCount() {
        Loan loan = new Loan("book1", "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15));
        // 5 days past due
        assertEquals(5, loan.daysOverdue(LocalDate.of(2025, 1, 20)));
    }

    @Test
    void rejectedNullConstructorArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new Loan(null, "member1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15)));
        assertThrows(IllegalArgumentException.class,
                () -> new Loan("book1", null, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15)));
        assertThrows(IllegalArgumentException.class,
                () -> new Loan("book1", "member1", null, LocalDate.of(2025, 1, 15)));
        assertThrows(IllegalArgumentException.class,
                () -> new Loan("book1", "member1", LocalDate.of(2025, 1, 1), null));
    }
}
