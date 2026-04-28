package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LibraryService class.
 * Tests borrow/return/search workflows.
 */
class LibraryServiceTest {

    private LibraryService service;
    private LoanPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new LoanPolicy(14, 50);
        service = new LibraryService(policy);

        // Add books
        service.addBook(new Book("B1", "Java Programming", "978-0-123", "John Smith", 3));
        service.addBook(new Book("B2", "Python Basics", "978-0-456", "Jane Doe", 2));
        service.addBook(new Book("B3", "Data Structures", "978-0-789", "Bob Wilson", 1));

        // Add members
        service.addMember(new Member("M1", "Alice", "alice@example.com", MembershipType.BASIC));
        service.addMember(new Member("M2", "Bob", "bob@example.com", MembershipType.PREMIUM));
    }

    @Test
    void addBookStoresBook() {
        assertTrue(service.getBook("B1").isPresent());
        assertEquals("Java Programming", service.getBook("B1").get().getTitle());
    }

    @Test
    void addMemberStoresMember() {
        assertTrue(service.getMember("M1").isPresent());
        assertEquals("Alice", service.getMember("M1").get().getName());
    }

    @Test
    void borrowBookCreatesLoan() {
        Loan loan = service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 1));
        assertNotNull(loan);
        assertEquals("B1", loan.getBookId());
        assertEquals("M1", loan.getMemberId());
    }

    @Test
    void borrowBookDecreasesAvailableCopies() {
        service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 1));
        assertEquals(2, service.getBook("B1").get().getAvailableCopies());
    }

    @Test
    void borrowBookSetsCorrectDueDate() {
        Loan loan = service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 1));
        assertEquals(LocalDate.of(2025, 1, 15), loan.getDueDate()); // 14 days later
    }

    @Test
    void borrowBookFailsForMemberNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> service.borrowBook("M999", "B1", LocalDate.of(2025, 1, 1)));
    }

    @Test
    void borrowBookFailsForBookNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> service.borrowBook("M1", "B999", LocalDate.of(2025, 1, 1)));
    }

    @Test
    void borrowBookFailsWhenNoCopiesAvailable() {
        service.borrowBook("M1", "B3", LocalDate.of(2025, 1, 1)); // B3 has only 1 copy
        assertThrows(IllegalStateException.class,
                () -> service.borrowBook("M2", "B3", LocalDate.of(2025, 1, 1)));
    }

    @Test
    void borrowBookRespectsMaxLoanLimitForBasic() {
        // BASIC members can borrow max 3 books
        service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 1));
        service.borrowBook("M1", "B2", LocalDate.of(2025, 1, 1));
        service.borrowBook("M1", "B3", LocalDate.of(2025, 1, 1));
        // Should fail because BASIC limit is 3
        assertThrows(IllegalStateException.class,
                () -> service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 2)),
                "BASIC member should not be able to borrow more than 3 books");
    }

    @Test
    void returnBookMarksLoanAsReturned() {
        Loan loan = service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 1));
        service.returnBook(loan.getId(), LocalDate.of(2025, 1, 10));
        assertEquals(LoanStatus.RETURNED, service.getLoan(loan.getId()).get().getStatus());
    }

    @Test
    void returnBookIncrementsAvailableCopies() {
        Loan loan = service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 1));
        assertEquals(2, service.getBook("B1").get().getAvailableCopies());

        service.returnBook(loan.getId(), LocalDate.of(2025, 1, 10));
        assertEquals(3, service.getBook("B1").get().getAvailableCopies());
    }

    @Test
    void returnBookFailsForNonExistentLoan() {
        assertThrows(IllegalArgumentException.class,
                () -> service.returnBook("nonexistent", LocalDate.of(2025, 1, 10)));
    }

    @Test
    void returnBookFailsForAlreadyReturnedLoan() {
        Loan loan = service.borrowBook("M1", "B1", LocalDate.of(2025, 1, 1));
        service.returnBook(loan.getId(), LocalDate.of(2025, 1, 10));
        assertThrows(IllegalStateException.class,
                () -> service.returnBook(loan.getId(), LocalDate.of(2025, 1, 11)));
    }

    @Test
    void searchBooksByTitle() {
        List<Book> results = service.searchBooks("Java");
        assertEquals(1, results.size());
        assertEquals("Java Programming", results.get(0).getTitle());
    }

    @Test
    void searchBooksByAuthor() {
        List<Book> results = service.searchBooks("Jane Doe");
        assertEquals(1, results.size());
        assertEquals("Python Basics", results.get(0).getTitle());
    }

    @Test
    void searchBooksByIsbn() {
        List<Book> results = service.searchBooks("978-0-789");
        assertEquals(1, results.size());
        assertEquals("Data Structures", results.get(0).getTitle());
    }

    @Test
    void searchBooksCaseInsensitive() {
        List<Book> results = service.searchBooks("java");
        assertEquals(1, results.size());
    }
}
