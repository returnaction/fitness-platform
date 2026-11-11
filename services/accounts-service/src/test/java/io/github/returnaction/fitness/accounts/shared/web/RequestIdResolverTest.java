package io.github.returnaction.fitness.accounts.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestIdResolverTest {

    @Test
    void keepsValidRequestId() {
        UUID requestId = UUID.randomUUID();

        assertThat(RequestIdResolver.parseOrGenerate(requestId.toString())).isEqualTo(requestId);
    }

    @Test
    void replacesMissingOrInvalidRequestId() {
        assertThat(RequestIdResolver.parseOrGenerate(null)).isNotNull();
        assertThat(RequestIdResolver.parseOrGenerate("not-a-uuid")).isNotNull();
    }
}
