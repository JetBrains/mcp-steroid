package com.example.demo;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CalculatorTest {

    @Test
    void addReturnsSum() {
        Calculator calc = new Calculator();
        Assertions.assertEquals(5, calc.add(2, 3));
    }

    @Test
    void multiplyReturnsProduct() {
        Calculator calc = new Calculator();
        Assertions.assertEquals(6, calc.multiply(2, 3));
    }
}
