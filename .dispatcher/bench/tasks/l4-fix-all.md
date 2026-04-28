# L4: Fix All Bugs in the Order Processing System

## Task

The order processing system has several bugs. Run `mvn test` to see failures.
Fix all bugs so all 33 tests pass. Read the source carefully to understand the domain.

## Files to Examine

- `src/main/java/com/example/Order.java` — Order model with item management
- `src/main/java/com/example/PricingEngine.java` — Pricing, discount, and tax calculations
- `src/main/java/com/example/InventoryTracker.java` — Stock reservation and release
- `src/main/java/com/example/OrderService.java` — Order lifecycle orchestration

**Do not modify:**
- `Product.java` — correct, no bugs
- `OrderItem.java` — correct, no bugs
- Any test files
- `pom.xml`

## Rules

1. Run `mvn test` first to see which tests fail.
2. Read the source code to understand the domain model and call chains.
3. Fix bugs using the Edit tool. Each bug may require understanding cross-file interactions.
4. Verify all 33 tests pass with `mvn test` after each fix.
5. Do not modify test files or `pom.xml`.

## Known Bug Areas (Natural Language)

- **Order.addItem()**: When adding an item for a product that already exists in the order, the quantities should be merged into a single line item rather than creating duplicate entries.
- **PricingEngine discount calculation**: Applying a percentage discount produces incorrect results — a 10% discount on $100 should yield $90, but the system returns $100.
- **PricingEngine empty order**: Calculating the total for an order with no items should return $0, not crash.
- **InventoryTracker release**: Releasing reserved stock should never result in available stock exceeding the product's maximum capacity.
- **OrderService placeOrder**: When placing an order fails due to insufficient stock on one item, any stock already reserved for earlier items in the same order must be rolled back.

## Success Criteria

`mvn test` exits with code 0 and all 33 tests pass.
