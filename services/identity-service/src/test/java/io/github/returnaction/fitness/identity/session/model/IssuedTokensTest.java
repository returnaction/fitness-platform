package io.github.returnaction.fitness.identity.session.model;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class IssuedTokensTest {
    @Test
    void doesNotExposeTokensInToString() {
        var tokens = new IssuedTokens("secret-access", "secret-refresh");
        assertThat(tokens.toString()).doesNotContain("secret-access", "secret-refresh");
    }
}
