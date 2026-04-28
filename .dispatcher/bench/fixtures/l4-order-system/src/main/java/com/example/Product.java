package com.example;

/**
 * Product domain model with stock tracking.
 */
public class Product {
    private final String id;
    private final String name;
    private final double price;
    private final int maxStock;
    private int reservedStock;

    public Product(String id, String name, double price, int maxStock) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.maxStock = maxStock;
        this.reservedStock = 0;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getPrice() {
        return price;
    }

    public int getMaxStock() {
        return maxStock;
    }

    public int getReservedStock() {
        return reservedStock;
    }

    public int getAvailableStock() {
        return maxStock - reservedStock;
    }

    /**
     * Reserve stock for this product.
     * @return true if reservation succeeded, false if insufficient stock
     */
    public boolean reserveStock(int quantity) {
        if (quantity > getAvailableStock()) {
            return false;
        }
        reservedStock += quantity;
        return true;
    }

    /**
     * Release previously reserved stock.
     */
    public void releaseStock(int quantity) {
        reservedStock -= quantity;
    }

    @Override
    public String toString() {
        return "Product{id='%s', name='%s', price=%.2f, maxStock=%d, reservedStock=%d}"
                .formatted(id, name, price, maxStock, reservedStock);
    }
}
