package com.example;

/**
 * Immutable product value object.
 */
public final class Product {
    private final String id;
    private final String name;
    private final String category;
    private final double price;
    private final int stockLevel;

    public Product(String id, String name, String category, double price, int stockLevel) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
        this.stockLevel = stockLevel;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public double getPrice() { return price; }
    public int getStockLevel() { return stockLevel; }

    public Product withStock(int newStock) {
        return new Product(id, name, category, price, newStock);
    }

    public Product withPrice(double newPrice) {
        return new Product(id, name, category, newPrice, stockLevel);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Product product = (Product) o;
        return Double.compare(product.price, price) == 0
            && stockLevel == product.stockLevel
            && id.equals(product.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Product{id='%s', name='%s', category='%s', price=%.2f, stockLevel=%d}"
            .formatted(id, name, category, price, stockLevel);
    }
}
