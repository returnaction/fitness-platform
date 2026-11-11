package io.github.returnaction.fitness.identity.shared.web;

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
    void replacesInvalidRequestId() {
        assertThat(RequestIdResolver.parseOrGenerate("not-a-uuid")).isNotNull();
    }
}
