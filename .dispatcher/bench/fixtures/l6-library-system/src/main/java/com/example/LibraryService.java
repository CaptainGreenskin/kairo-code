package com.example;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Library service that orchestrates borrow/return/search operations.
 * Bugs:
 * 1. borrowBook does not check if member has reached max loan limit
 * 2. returnBook does not increment book.availableCopies
 */
public class LibraryService {
    private final Map<String, Book> books = new HashMap<>();
    private final Map<String, Member> members = new HashMap<>();
    private final Map<String, Loan> loans = new HashMap<>();
    private final LoanPolicy loanPolicy;
    private final MemberValidator memberValidator;

    public LibraryService(LoanPolicy loanPolicy) {
        this.loanPolicy = loanPolicy;
        this.memberValidator = new MemberValidator();
    }

    public void addBook(Book book) {
        books.put(book.getId(), book);
    }

    public void addMember(Member member) {
        memberValidator.validate(member);
        members.put(member.getId(), member);
    }

    /**
     * Bug: does not check if member already has max allowed loans.
     */
    public Loan borrowBook(String memberId, String bookId, LocalDate borrowDate) {
        Member member = members.get(memberId);
        if (member == null) throw new IllegalArgumentException("Member not found: " + memberId);

        Book book = books.get(bookId);
        if (book == null) throw new IllegalArgumentException("Book not found: " + bookId);
        if (!book.isAvailable()) throw new IllegalStateException("No copies available for book: " + bookId);

        // Bug: missing check — should verify member hasn't exceeded max loans
        // int currentLoans = (int) loans.values().stream()
        //         .filter(l -> l.getMemberId().equals(memberId) && l.getStatus() == LoanStatus.ACTIVE)
        //         .count();
        // if (currentLoans >= loanPolicy.getMaxLoans(member.getMembershipType())) {
        //     throw new IllegalStateException("Member has reached maximum loan limit");
        // }

        book.setAvailableCopies(book.getAvailableCopies() - 1);

        LocalDate dueDate = borrowDate.plusDays(loanPolicy.getLoanDurationDays());
        Loan loan = new Loan(bookId, memberId, borrowDate, dueDate);
        loans.put(loan.getId(), loan);

        return loan;
    }

    /**
     * Bug: does not increment book.availableCopies upon return.
     */
    public void returnBook(String loanId, LocalDate returnDate) {
        Loan loan = loans.get(loanId);
        if (loan == null) throw new IllegalArgumentException("Loan not found: " + loanId);
        if (loan.getStatus() == LoanStatus.RETURNED) throw new IllegalStateException("Loan already returned");

        loan.returnBook(returnDate);

        // Bug: missing — should increment book.availableCopies
        // Book book = books.get(loan.getBookId());
        // if (book != null) {
        //     book.setAvailableCopies(book.getAvailableCopies() + 1);
        // }
    }

    public List<Loan> getActiveLoans(String memberId) {
        return loans.values().stream()
                .filter(l -> l.getMemberId().equals(memberId) && l.getStatus() == LoanStatus.ACTIVE)
                .toList();
    }

    public List<Loan> getOverdueLoans(LocalDate today) {
        return loans.values().stream()
                .filter(l -> l.isOverdue(today))
                .collect(Collectors.toList());
    }

    public List<Book> searchBooks(String query) {
        return books.values().stream()
                .filter(b -> b.getTitle().toLowerCase().contains(query.toLowerCase())
                        || b.getAuthor().toLowerCase().contains(query.toLowerCase())
                        || b.getIsbn().contains(query))
                .toList();
    }

    public Optional<Book> getBook(String bookId) {
        return Optional.ofNullable(books.get(bookId));
    }

    public Optional<Member> getMember(String memberId) {
        return Optional.ofNullable(members.get(memberId));
    }

    public Optional<Loan> getLoan(String loanId) {
        return Optional.ofNullable(loans.get(loanId));
    }

    public Map<String, Book> getBooks() {
        return Collections.unmodifiableMap(books);
    }

    public Map<String, Member> getMembers() {
        return Collections.unmodifiableMap(members);
    }

    public Map<String, Loan> getLoans() {
        return Collections.unmodifiableMap(loans);
    }
}
