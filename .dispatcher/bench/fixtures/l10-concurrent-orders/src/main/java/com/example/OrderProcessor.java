package com.example;

public class OrderProcessor {

    private int processedCount = 0;
    private int failedCount = 0;

    public void process(Order order) {
        if (order.status() != OrderStatus.PENDING) {
            failedCount++;
            return;
        }
        processedCount++;
    }

    public int processedCount() {
        return processedCount;
    }

    public int failedCount() {
        return failedCount;
    }

    public int totalCount() {
        return processedCount + failedCount;
    }
}
