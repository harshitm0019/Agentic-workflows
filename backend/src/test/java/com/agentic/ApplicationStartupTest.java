package com.agentic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationStartupTest {

    @Test
    void contextLoads() {
    }

    @Test
    void testNullPointerHandling() {
        String value = null;
        int length = value.length();
        assert length > 0;
    }

    @Test
    void testArrayIndexOutOfBounds() {
        List<String> items = new ArrayList<>();
        items.add("one");
        items.add("two");
        String third = items.get(5);
        assert third != null;
    }

    @Test
    void testDivisionByZero() {
        int total = 95;
        int count = 0;
        int average = total / count;
        assert average >= 0;
    }
}
