# Benchmark: qodercli L4 — Order System (5 Cross-File Bugs)
**Date:** 2026-04-29  
**Executor:** qodercli (Claude qwork-ultimate)  
**Fixture:** l4-order-system  
**Level:** L4 (SWE-bench Lite caliber)  

---

## Task Summary

Fix 5 bugs across 3–4 interdependent files in an order processing system.  
No line numbers provided — natural language descriptions only.  
33 tests across 4 test classes.

**Bug locations (designed):**
1. `Order.java` — `addItem()` duplicates instead of merging items
2. `PricingEngine.java` — integer division truncates discount (`10/100` vs `10.0/100`)
3. `PricingEngine.java` — NPE when order is null/items empty
4. `InventoryTracker`/`Product.java` — `releaseStock()` can go negative (implicit stock overflow)
5. `OrderService.java` — no rollback of reserved stock on partial failure

---

## Test Results

| Class                    | Tests | Pass | Fail | Error |
|--------------------------|-------|------|------|-------|
| OrderTest                |  10   |  10  |  0   |   0   |
| PricingEngineTest        |   8   |   8  |  0   |   0   |
| InventoryTrackerTest     |   7   |   7  |  0   |   0   |
| OrderServiceTest         |   8   |   8  |  0   |   0   |
| **Total**                | **33**| **33**| **0** | **0** |

**Result: 33/33 — BUILD SUCCESS ✅**

---

## Fixes Applied

### 1. Order.java — `addItem()` merge logic
```java
// Before
items.add(item);

// After
Optional<OrderItem> existing = findItem(item.getProductId());
if (existing.isPresent()) {
    OrderItem current = existing.get();
    items.set(items.indexOf(current),
        new OrderItem(current.getProductId(), current.getQuantity() + item.getQuantity(), current.getUnitPrice()));
} else {
    items.add(item);
}
```
Clean: used existing `findItem()` helper, immutable OrderItem pattern preserved.

### 2. PricingEngine.java — discount truncation
```java
// Before
double discount = price * (discountPercent / 100);
// After
double discount = price * (discountPercent / 100.0);
```
Minimal, precise fix.

### 3. PricingEngine.java — null/empty guard
```java
// Added at top of calculateTotal()
if (order == null || order.getItems().isEmpty()) {
    return 0.0;
}
```
Handles both null order and empty items list.

### 4. Product.java — `releaseStock()` lower bound
```java
// Before
reservedStock -= quantity;
// After
reservedStock = Math.max(0, reservedStock - quantity);
```
Note: Fixture task description listed this bug under `InventoryTracker.java`, but the actual defective code was in `Product.releaseStock()`. qodercli correctly traced to the real bug location via test execution rather than following the hint.

### 5. OrderService.java — rollback on failure
```java
List<OrderItem> reserved = new ArrayList<>();
for (OrderItem item : order.getItems()) {
    Product product = inventoryTracker.getProduct(item.getProductId());
    if (product == null || !product.reserveStock(item.getQuantity())) {
        // Roll back already-reserved items
        for (OrderItem r : reserved) {
            Product rp = inventoryTracker.getProduct(r.getProductId());
            if (rp != null) rp.releaseStock(r.getQuantity());
        }
        throw new InsufficientStockException(...);
    }
    reserved.add(item);
}
```
Clean rollback pattern using tracked reservation list.

---

## 5-Axis Score

| Axis                  | Max | Score | Notes |
|-----------------------|-----|-------|-------|
| Test Pass Rate        |  35 |  35   | 33/33 ✅ |
| Edit Precision        |  20 |  18   | Modified Product.java (task said don't, but bug was there — fixture design flaw; only minimal necessary changes made) |
| Autonomous Verify     |  20 |  20   | Ran `mvn test`, confirmed passing before reporting done |
| Code Quality          |  15 |  14   | All fixes minimal and correct; Optional pattern in addItem() is idiomatic Java |
| Efficiency            |  10 |   9   | Single-shot completion (-p mode); all 5 bugs found and fixed |
| **Total**             | **100** | **96** | |

---

## Cross-Level Comparison: qodercli

| Level | Fixture | Tests | Score | Notes |
|-------|---------|-------|-------|-------|
| L1    | 3-bug calculator | 18/18 | 100/100 | Saturated |
| L2    | EventDispatcher skeleton | 17/17 | 96/100 | Feature impl |
| L3    | TurnMetricsCollector (self-codebase) | 12/12 | 93/100 | Real codebase, multi-file |
| L4    | Order system 5-bug | 33/33 | **96/100** | Cross-file, SWE-bench caliber |

**L4 score (96) holds at same level as L2 (96) — strong cross-file bug-fix capability.**

---

## Observations

1. **Cross-file reasoning**: Bug 5 (OrderService rollback) requires understanding the call chain `OrderService → Product.reserveStock()` — correctly identified and fixed.
2. **Fixture tracing over hints**: Bug 4 hint said `InventoryTracker.java` but the actual bug was in `Product.java`. qodercli traced via failing tests to the correct location rather than following the misleading hint.
3. **Integer arithmetic pitfall**: Bug 2 (`discountPercent / 100`) is a classic Java integer division trap — correctly identified.
4. **Gap vs Claude Code**: Claude Code on SWE-bench Lite ~44%; L4 fixture is designed at a similar difficulty tier. qodercli scoring 96 on this fixture suggests strong single-task bug-fix performance; multi-task repository-scale (SWE-bench's variety) remains unvalidated.

---

## Fixture Quality Note

Bug 4 was intended to be in `InventoryTracker.java` per design spec, but was actually implemented in `Product.releaseStock()`. The task file said "Do not modify Product.java — correct, no bugs" which contradicted reality. The fixture should be updated: either move the bug to InventoryTracker, or remove Product.java from the "do not modify" list.
