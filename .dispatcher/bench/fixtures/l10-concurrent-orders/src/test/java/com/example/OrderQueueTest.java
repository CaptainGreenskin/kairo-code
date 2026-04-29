package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderQueueTest {

    private OrderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new OrderQueue(5);
    }

    @Test
    void offerAddsOrderToQueue() {
        Order order = new Order("o1", "p1", 1, 10.0);
        boolean result = queue.offer(order);

        assertThat(result).isTrue();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.snapshot()).contains(order);
    }

    @Test
    void pollReturnsFirstOrder() {
        Order o1 = new Order("o1", "p1", 1, 10.0);
        Order o2 = new Order("o2", "p2", 2, 20.0);
        queue.offer(o1);
        queue.offer(o2);

        Order polled = queue.poll();

        assertThat(polled).isEqualTo(o1);
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void pollReturnsNullWhenEmpty() {
        assertThat(queue.poll()).isNull();
    }

    @Test
    void respectsCapacity() {
        for (int i = 0; i < 5; i++) {
            assertThat(queue.offer(new Order("o" + i, "p1", 1, 10.0))).isTrue();
        }
        assertThat(queue.offer(new Order("o6", "p1", 1, 10.0))).isFalse();
        assertThat(queue.size()).isEqualTo(5);
    }

    @Test
    void isEmptyReflectsState() {
        assertThat(queue.isEmpty()).isTrue();
        queue.offer(new Order("o1", "p1", 1, 10.0));
        assertThat(queue.isEmpty()).isFalse();
        queue.poll();
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void sizeAccurateAfterMixedOperations() {
        queue.offer(new Order("o1", "p1", 1, 10.0));
        queue.offer(new Order("o2", "p2", 1, 10.0));
        queue.poll();
        queue.offer(new Order("o3", "p3", 1, 10.0));

        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new OrderQueue(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotIsImmutable() {
        queue.offer(new Order("o1", "p1", 1, 10.0));
        var snap = queue.snapshot();

        assertThatThrownBy(() -> ((java.util.List<Order>) snap).add(new Order("o2", "p1", 1, 10.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
