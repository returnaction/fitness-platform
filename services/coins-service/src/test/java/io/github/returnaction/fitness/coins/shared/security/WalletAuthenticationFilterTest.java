package io.github.returnaction.fitness.coins.shared.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WalletAuthenticationFilterTest {

    private final SecurityExceptionHandler errors = mock(SecurityExceptionHandler.class);
    private final WalletAuthenticationFilter filter = new WalletAuthenticationFilter(errors);

    @BeforeEach
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesOwnerAndKnownRolesOnlyDuringRequest() throws Exception {
        UUID owner = UUID.randomUUID();
        var request = request(token(owner.toString(), List.of("USER", "ADMIN", "UNKNOWN")));

        filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.isAuthenticated()).isTrue();
            assertThat(authentication.getPrincipal()).isEqualTo(owner);
            assertThat(authentication.getCredentials()).isNull();
            assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER", "ROLE_ADMIN");
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(errors);
    }

    @Test
    void clearsContextWhenDownstreamThrows() throws Exception {
        var request = request(token(UUID.randomUUID().toString(), null));
        var failure = new ServletException("controller failed");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            throw failure;
        })).isSameAs(failure);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(errors);
    }

    @Test
    void consecutiveRequestsHaveSeparateOwnersAndNoInheritedRoles() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        filter.doFilter(request(token(first.toString(), List.of("ADMIN"))), new MockHttpServletResponse(),
            (incoming, outgoing) -> assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(first));

        filter.doFilter(request(token(second.toString(), null)), new MockHttpServletResponse(), (incoming, outgoing) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getPrincipal()).isEqualTo(second);
            assertThat(authentication.getAuthorities()).isEmpty();
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(errors);
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void rejectsInvalidAuthenticationWithoutCallingDownstream(String token) throws Exception {
        var request = request(token);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, List.of())
        );

        filter.doFilter(request, response, chain);

        verify(errors).commence(same(request), same(response), any(BadCredentialsException.class));
        verifyNoInteractions(chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<String> invalidTokens() throws Exception {
        String owner = UUID.randomUUID().toString();
        return Stream.of(
            null,
            "malformed",
            token(null, null),
            token("not-a-uuid", null),
            token("1-1-1-1-1", null),
            token(owner, "ADMIN"),
            token(owner, List.of(123))
        );
    }

    private static MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest("GET", "/api/v1/coins/me");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    private static String token(String subject, Object roles) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject);
        if (roles != null) {
            claims.claim("roles", roles);
        }
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        token.sign(new MACSigner(new byte[32]));
        return token.serialize();
    }
}
