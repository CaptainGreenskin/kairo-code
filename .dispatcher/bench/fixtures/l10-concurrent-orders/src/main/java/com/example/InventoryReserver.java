package com.example;

import java.util.HashMap;
import java.util.Map;

public class InventoryReserver {

    private final Map<String, Integer> reserved = new HashMap<>();
    private final Map<String, Integer> available = new HashMap<>();

    public void initProduct(String productId, int initialAvailable) {
        reserved.put(productId, 0);
        available.put(productId, initialAvailable);
    }

    public boolean reserve(String productId, int quantity) {
        int avail = available.getOrDefault(productId, 0);
        if (avail >= quantity) {
            reserved.merge(productId, quantity, Integer::sum);
            available.put(productId, avail - quantity);
            return true;
        }
        return false;
    }

    public void release(String productId, int quantity) {
        int res = reserved.getOrDefault(productId, 0);
        if (res >= quantity) {
            reserved.put(productId, res - quantity);
            available.merge(productId, quantity, Integer::sum);
        }
    }

    public int reserved(String productId) {
        return reserved.getOrDefault(productId, 0);
    }

    public int available(String productId) {
        return available.getOrDefault(productId, 0);
    }

    public int total(String productId) {
        return reserved(productId) + available(productId);
    }
}
