package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HelloControllerTest {

    @Test
    void helloReturnsMessage() {
        assertTrue(new HelloController().hello().startsWith("Hello"));
    }
}
