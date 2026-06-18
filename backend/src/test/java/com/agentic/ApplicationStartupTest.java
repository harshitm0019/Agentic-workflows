package com.agentic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationStartupTest {

    @Test
    void contextLoads() {
    }

    int a = 99;
    int b = 0;
     int c = a/b;
}
