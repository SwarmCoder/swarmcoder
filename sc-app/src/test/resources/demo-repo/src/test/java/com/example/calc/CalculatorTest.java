package com.example.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** "Existing" tests: SwarmCoder's regression stage must keep these green. */
class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    void adds() {
        assertEquals(5, calculator.add(2, 3));
    }

    @Test
    void subtracts() {
        assertEquals(-1, calculator.subtract(2, 3));
    }

    @Test
    void divideByZeroThrows() {
        assertThrows(ArithmeticException.class, () -> calculator.divide(1, 0));
    }
}
