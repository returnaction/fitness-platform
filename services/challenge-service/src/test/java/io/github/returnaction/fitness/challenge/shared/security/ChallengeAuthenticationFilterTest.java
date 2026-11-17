package io.github.returnaction.fitness.challenge.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ChallengeAuthenticationFilterTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final ChallengeAuthenticationFilter filter = new ChallengeAuthenticationFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesChallengePathsAndClearsContextAfterHandling() throws Exception {
        for (String path : List.of("/api/v1/challenges/" + USER_ID,
                "/api/v1/challenge-operations/" + UUID.randomUUID())) {
            var captured = new AtomicReference<Authentication>();
            var request = request(path);
            request.addHeader("Authorization", "Bearer " + token(USER_ID.toString(), List.of("USER", "UNKNOWN")));

            filter.doFilter(request, new MockHttpServletResponse(), capture(captured));

            assertThat(captured.get()).as(path).isNotNull();
            assertThat(captured.get().getPrincipal()).isEqualTo(USER_ID);
            assertThat(captured.get().getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void leavesContextEmptyForMissingOrMalformedToken() throws Exception {
        List<String> invalidAuthorizations = Arrays.asList(
                null,
                "Basic abc",
                "Bearer ",
                "Bearer not-a-jwt",
                "Bearer " + token(new JWTClaimsSet.Builder().claim("roles", List.of("USER")).build()),
                "Bearer " + token(new JWTClaimsSet.Builder().subject("not-a-uuid").build()),
                "Bearer " + token(new JWTClaimsSet.Builder()
                        .subject(USER_ID.toString())
                        .claim("roles", "USER")
                        .build()));

        for (String authorization : invalidAuthorizations) {
            var captured = new AtomicReference<Authentication>();
            var request = request("/api/v1/challenges/" + USER_ID);
            if (authorization != null) {
                request.addHeader("Authorization", authorization);
            }

            filter.doFilter(request, new MockHttpServletResponse(), capture(captured));

            assertThat(captured.get()).as(String.valueOf(authorization)).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void skipsActuatorAndUnknownPaths() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/prometheus", "/unknown")) {
            var captured = new AtomicReference<Authentication>();
            filter.doFilter(request(path), new MockHttpServletResponse(), capture(captured));
            assertThat(captured.get()).as(path).isNull();
        }
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static FilterChain capture(AtomicReference<Authentication> captured) {
        return (request, response) -> captured.set(SecurityContextHolder.getContext().getAuthentication());
    }

    private static String token(String subject, List<String> roles) {
        return token(new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", roles)
                .build());
    }

    private static String token(JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(new byte[32]));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
