package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorTest {
    private final Calculator calc = new Calculator();

    @Test void testAdd() { assertEquals(5, calc.add(2, 3)); }

    @Test void testSubtract() { assertEquals(2, calc.subtract(5, 3)); }  // FAILS

    @Test void testMultiply() { assertEquals(15, calc.multiply(3, 5)); }

    @Test void testDivide() { assertEquals(4, calc.divide(12, 3)); }

    @Test void testDivideByZero() {
        assertThrows(IllegalArgumentException.class, () -> calc.divide(1, 0));
    }
}
