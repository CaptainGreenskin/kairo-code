# Implement EventDispatcher

This Maven project has a skeleton `EventDispatcher.java` with all methods throwing `UnsupportedOperationException`. Implement it fully so all tests pass.

## Requirements

Read the Javadoc in `EventDispatcher.java` carefully — it specifies exact behavior:

- `subscribe(Class<T>, EventHandler<T>)` — register handler for exact event type
- `unsubscribe(Class<T>, EventHandler<T>)` — remove first matching handler (by `==` reference)
- `dispatch(T)` — synchronous; call handlers in subscription order; on first exception wrap in `EventDeliveryException` and stop
- `dispatchAsync(T, Executor)` — all handlers run independently; all must be attempted even if one fails; return `CompletableFuture<Void>`
- `subscriberCount(Class<?>)` — count for exact type
- `clearAll()` — remove all subscriptions
- `registeredTypes()` — list of all types with at least one subscriber

## Files

- `src/main/java/com/example/EventDispatcher.java` — implement this
- `src/main/java/com/example/EventHandler.java` — do not modify
- `src/main/java/com/example/EventDeliveryException.java` — do not modify
- `src/test/java/com/example/EventDispatcherTest.java` — do not modify

## Rules

- Do NOT modify test files or other support classes.
- Success = `mvn test` exits 0 (all 17 tests pass).
- Run `mvn test` to verify before finishing.
