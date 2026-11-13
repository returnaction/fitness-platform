package io.github.returnaction.fitness.accounts.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;

class SecurityExceptionHandlerTest {

    @Test
    void unresolvedFailureReturnsEmpty500WithoutLeakingException() {
        var handler = new SecurityExceptionHandler((request, response, controller, exception) -> null);
        var response = new MockHttpServletResponse();
        handler.commence(new MockHttpServletRequest(), response, new BadCredentialsException("secret-token"));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void committedResponseIsNotRewritten() throws Exception {
        var handler = new SecurityExceptionHandler((request, response, controller, exception) -> {
            throw new AssertionError("Committed responses must not reach the resolver");
        });
        var response = new MockHttpServletResponse();
        response.setStatus(403);
        response.getWriter().write("already sent");
        response.flushBuffer();
        handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("private"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }
}
