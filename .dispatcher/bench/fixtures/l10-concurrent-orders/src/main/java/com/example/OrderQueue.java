package com.example;

import java.util.ArrayList;
import java.util.List;

public class OrderQueue {

    private final List<Order> queue = new ArrayList<>();
    private final int capacity;

    public OrderQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public boolean offer(Order order) {
        if (queue.size() < capacity) {
            queue.add(order);
            return true;
        }
        return false;
    }

    public Order poll() {
        if (!queue.isEmpty()) {
            return queue.remove(0);
        }
        return null;
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public List<Order> snapshot() {
        return List.copyOf(queue);
    }
}
