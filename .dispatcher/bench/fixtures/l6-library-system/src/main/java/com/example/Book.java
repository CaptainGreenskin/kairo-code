package com.example;

import java.util.Objects;

/**
 * Represents a book in the library.
 */
public class Book {
    private final String id;
    private final String title;
    private final String isbn;
    private final String author;
    private final int totalCopies;
    private int availableCopies;

    public Book(String id, String title, String isbn, String author, int totalCopies) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        if (isbn == null || isbn.isBlank()) throw new IllegalArgumentException("isbn must not be blank");
        if (author == null || author.isBlank()) throw new IllegalArgumentException("author must not be blank");
        if (totalCopies <= 0) throw new IllegalArgumentException("totalCopies must be positive");

        this.id = id;
        this.title = title;
        this.isbn = isbn;
        this.author = author;
        this.totalCopies = totalCopies;
        this.availableCopies = totalCopies;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getIsbn() { return isbn; }
    public String getAuthor() { return author; }
    public int getTotalCopies() { return totalCopies; }
    public int getAvailableCopies() { return availableCopies; }

    public void setAvailableCopies(int availableCopies) {
        if (availableCopies < 0 || availableCopies > totalCopies) {
            throw new IllegalArgumentException("availableCopies out of range");
        }
        this.availableCopies = availableCopies;
    }

    public boolean isAvailable() {
        return availableCopies > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Book book = (Book) o;
        return Objects.equals(id, book.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Book{id='%s', title='%s', author='%s'}".formatted(id, title, author);
    }
}
