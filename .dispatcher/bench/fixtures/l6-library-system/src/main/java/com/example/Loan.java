package com.example;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a book loan.
 * Bug: isOverdue() does not check returnedAt — already returned loans are marked overdue.
 */
public class Loan {
    private final String id;
    private final String bookId;
    private final String memberId;
    private final LocalDate borrowedAt;
    private final LocalDate dueDate;
    private LocalDate returnedAt;
    private LoanStatus status;

    public Loan(String bookId, String memberId, LocalDate borrowedAt, LocalDate dueDate) {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId must not be blank");
        if (memberId == null || memberId.isBlank()) throw new IllegalArgumentException("memberId must not be blank");
        if (borrowedAt == null) throw new IllegalArgumentException("borrowedAt must not be null");
        if (dueDate == null) throw new IllegalArgumentException("dueDate must not be null");

        this.id = UUID.randomUUID().toString();
        this.bookId = bookId;
        this.memberId = memberId;
        this.borrowedAt = borrowedAt;
        this.dueDate = dueDate;
        this.returnedAt = null;
        this.status = LoanStatus.ACTIVE;
    }

    public String getId() { return id; }
    public String getBookId() { return bookId; }
    public String getMemberId() { return memberId; }
    public LocalDate getBorrowedAt() { return borrowedAt; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getReturnedAt() { return returnedAt; }
    public LoanStatus getStatus() { return status; }

    public void setStatus(LoanStatus status) {
        this.status = status;
    }

    public void returnBook(LocalDate returnDate) {
        this.returnedAt = returnDate;
        this.status = LoanStatus.RETURNED;
    }

    /**
     * Bug: does not check returnedAt — returned loans are still considered overdue.
     */
    public boolean isOverdue(LocalDate today) {
        return today.isAfter(dueDate);
    }

    public long daysOverdue(LocalDate today) {
        if (!isOverdue(today)) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(dueDate, today);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Loan loan = (Loan) o;
        return Objects.equals(id, loan.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Loan{id='%s', bookId='%s', memberId='%s', status=%s}".formatted(id, bookId, memberId, status);
    }
}
