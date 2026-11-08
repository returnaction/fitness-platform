package io.github.returnaction.fitness.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

class LombokConfigurationTest {

    @Test
    void generatesConstructorAndGetter() {
        assertEquals("identity", new Example("identity").getName());
    }

    @Getter
    @RequiredArgsConstructor
    private static class Example {
        private final String name;
    }
}
