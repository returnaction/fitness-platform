package io.github.returnaction.fitness.identity.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.ModelAndView;

class LogoutAllAuthenticationFilterTest {

    private final LogoutAllAuthenticationFilter filter = new LogoutAllAuthenticationFilter(
            (request, response, handler, failure) -> {
                response.setStatus(401);
                return new ModelAndView();
            });

    @Test
    void acceptsGatewaySubjectWithoutCheckingSignatureExpiryOrRolesAndClearsContext() throws Exception {
        UUID user = UUID.randomUUID();
        String jwt = token("{\"sub\":\"" + user + "\",\"exp\":1,\"roles\":[\"UNKNOWN\"]}");
        filter.doFilter(request(jwt), new MockHttpServletResponse(), (request, response) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.isAuthenticated()).isTrue();
            assertThat(authentication.getPrincipal()).isEqualTo(user);
            assertThat(authentication.getAuthorities()).isEmpty();
            assertThat(authentication.getCredentials()).isNull();
        });
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        var response = new MockHttpServletResponse();
        filter.doFilter(request(null), response, (req, res) -> {
            throw new AssertionError("Missing token accepted");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void clearsContextOnDownstreamFailure() {
        String jwt = token("{\"sub\":\"" + UUID.randomUUID() + "\"}");
        assertThatThrownBy(() -> filter.doFilter(request(jwt), new MockHttpServletResponse(), (request, response) -> {
            throw new ServletException("downstream failure");
        })).isInstanceOf(ServletException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"sub\":null}", "{\"sub\":42}", "{\"sub\":\"bad\"}", "{\"sub\":\"1-1-1-1-1\"}"})
    void rejectsMissingOrInvalidSubject(String claims) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(token(claims)), response, (req, res) -> {
            throw new AssertionError("Invalid subject accepted");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad", "a.b.c", "eyJhbGciOiJub25lIn0.e30."})
    void rejectsMalformedToken(String jwt) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(jwt), response, (req, res) -> {
            throw new AssertionError("Malformed token accepted");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void isolatesUsersInParallelRequests() throws Exception {
        var barrier = new java.util.concurrent.CountDownLatch(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<UUID> call = () -> {
                UUID user = UUID.randomUUID();
                filter.doFilter(request(token("{\"sub\":\"" + user + "\"}")), new MockHttpServletResponse(),
                        (request, response) -> {
                            barrier.countDown();
                            try {
                                assertThat(barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new ServletException(exception);
                            }
                            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                                    .isEqualTo(user);
                        });
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                return user;
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isNotEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static MockHttpServletRequest request(String jwt) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/logout-all");
        if (jwt != null)
            request.addHeader("Authorization", "Bearer " + jwt);
        return request;
    }

    private static String token(String claims) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".AA";
    }
}
