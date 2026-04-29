package com.example;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PaymentLedger {

    private final Map<String, Double> payments = new HashMap<>();
    private final Map<String, Double> totalByProduct = new HashMap<>();

    public void record(String orderId, String productId, double amount) {
        payments.put(orderId, amount);
        totalByProduct.merge(productId, amount, Double::sum);
    }

    public Optional<Double> getPayment(String orderId) {
        return Optional.ofNullable(payments.get(orderId));
    }

    public double totalByProduct(String productId) {
        return totalByProduct.getOrDefault(productId, 0.0);
    }

    public int paymentCount() {
        return payments.size();
    }

    public double grandTotal() {
        return payments.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public Map<String, Double> paymentsSnapshot() {
        return Map.copyOf(payments);
    }

    public Map<String, Double> totalsByProductSnapshot() {
        return Map.copyOf(totalByProduct);
    }
}
