package io.github.returnaction.fitness.identity.security.jwt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.identity.shared.exception.InvalidAccessTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/** Изолированный потребитель: тестовые endpoint не входят в приложение. */
class RoleProtectedEndpointTest {
    private static final String ISSUER = "https://identity.test";
    private static final String AUDIENCE = "gateway";
    private ECPrivateKey privateKey;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        privateKey = (ECPrivateKey) pair.getPrivate();
        var publicKey = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic())
                .keyID("key-1")
                .build();
        var verifier = new AccessTokenVerifier(new PublicJwtKeys(publicKey.toJSONObject()), ISSUER, AUDIENCE, "key-1");
        mvc = MockMvcBuilders.standaloneSetup(new TestEndpoint())
                .addFilters(new TestConsumerFilter(verifier))
                .build();
    }

    @Test
    void checksEveryLevelOfTheHierarchyWithoutExpandingTheToken() throws Exception {
        String[] roles = {"USER", "MODERATOR", "ADMIN", "SUPER_ADMIN"};
        for (int assigned = 0; assigned < roles.length; assigned++) {
            String token = token(List.of(roles[assigned]), ISSUER, AUDIENCE, Instant.now().plusSeconds(300), "key-1");
            for (int required = 0; required < roles.length; required++) {
                int expected = assigned >= required ? 200 : 403;
                mvc.perform(get("/test/" + roles[required]).header("Authorization", "Bearer " + token))
                        .andExpect(status().is(expected));
            }
            org.assertj.core.api.Assertions.assertThat(SignedJWT.parse(token)
                    .getJWTClaimsSet()
                    .getStringListClaim("roles")).containsExactly(roles[assigned]);
        }
    }

    @Test
    void acceptsRoleClaimProducedByIdentitySigner() throws Exception {
        var signer = new AccessTokenSigner(privateKey, "key-1", ISSUER, AUDIENCE, java.time.Clock.systemUTC());
        String token = signer.sign(UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(300),
                List.of(io.github.returnaction.fitness.identity.registration.entity.role.RoleCode.ADMIN));
        mvc.perform(get("/test/MODERATOR").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/test/SUPER_ADMIN").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void handlesMultipleUnknownAndMissingRoles() throws Exception {
        check(List.of("USER", "ADMIN"), "MODERATOR", 200);
        check(List.of("UNKNOWN", "USER"), "USER", 200);
        check(List.of("UNKNOWN"), "USER", 403);
        check(List.of(), "USER", 403);
        check(null, "USER", 403);
        check("ADMIN", "USER", 401);
        check(List.of("ADMIN", 1), "USER", 401);
    }

    @Test
    void rejectsInvalidAuthenticationBeforeUsingRoles() throws Exception {
        mvc.perform(get("/test/USER")).andExpect(status().isUnauthorized());
        checkToken("broken", 401);
        checkToken(token(List.of("ADMIN"), "wrong", AUDIENCE, Instant.now().plusSeconds(300), "key-1"), 401);
        checkToken(token(List.of("ADMIN"), ISSUER, "wrong", Instant.now().plusSeconds(300), "key-1"), 401);
        checkToken(token(List.of("ADMIN"), ISSUER, AUDIENCE, Instant.now().minusSeconds(1), "key-1"), 401);
        checkToken(token(List.of("ADMIN"), ISSUER, AUDIENCE, Instant.now().plusSeconds(300), "unknown"), 401);
        String valid = token(List.of("ADMIN"), ISSUER, AUDIENCE, Instant.now().plusSeconds(300), "key-1");
        String[] parts = valid.split("\\.");
        String altered = parts[0] + "." + java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"roles\":[\"SUPER_ADMIN\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + parts[2];
        checkToken(altered, 401);
    }

    private void check(Object roles, String required, int expected) throws Exception {
        mvc.perform(get("/test/" + required).header("Authorization", "Bearer "
                + token(roles, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), "key-1")))
                .andExpect(status().is(expected));
    }

    private void checkToken(String token, int expected) throws Exception {
        mvc.perform(get("/test/USER").header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected));
    }

    private String token(Object roles, String issuer, String audience, Instant expiry, String keyId) throws Exception {
        Instant issued = expiry.isBefore(Instant.now()) ? expiry.minusSeconds(60) : Instant.now().minusSeconds(1);
        var builder = new JWTClaimsSet.Builder().issuer(issuer)
                .audience(audience)
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issued))
                .expirationTime(Date.from(expiry));
        if (roles != null)
            builder.claim("roles", roles);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), builder.build());
        jwt.sign(new ECDSASigner(privateKey));
        return jwt.serialize();
    }
}

@RestController
class TestEndpoint {
    @GetMapping("/test/{role}")
    String secured(@PathVariable String role) {
        return "ok";
    }
}

/** Только тестовый пример потребителя, не production-фильтр другого сервиса. */
class TestConsumerFilter extends OncePerRequestFilter {
    private final AccessTokenVerifier verifier;
    private final RoleHierarchyImpl hierarchy = RoleHierarchyImpl.fromHierarchy(
            "ROLE_SUPER_ADMIN > ROLE_ADMIN\nROLE_ADMIN > ROLE_MODERATOR\nROLE_MODERATOR > ROLE_USER");
    private static final List<String> KNOWN = List.of("USER", "MODERATOR", "ADMIN", "SUPER_ADMIN");

    TestConsumerFilter(AccessTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            response.setStatus(401);
            return;
        }
        try {
            String token = authorization.substring(7);
            verifier.verify(token);
            var claims = SignedJWT.parse(token).getJWTClaimsSet();
            Object value = claims.getClaim("roles");
            if (claims.getClaims().containsKey("roles") && !(value instanceof List<?>)) {
                response.setStatus(401);
                return;
            }
            List<?> roles = value == null ? List.of() : (List<?>) value;
            if (roles.stream().anyMatch(role -> !(role instanceof String))) {
                response.setStatus(401);
                return;
            }
            var authorities = roles.stream()
                    .map(String.class::cast)
                    .filter(KNOWN::contains)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            String required = "ROLE_" + request.getRequestURI().substring("/test/".length());
            boolean allowed = hierarchy.getReachableGrantedAuthorities(authorities)
                    .stream()
                    .anyMatch(authority -> authority.getAuthority().equals(required));
            if (!allowed) {
                response.setStatus(403);
                return;
            }
            chain.doFilter(request, response);
        } catch (InvalidAccessTokenException | java.text.ParseException exception) {
            response.setStatus(401);
        }
    }
}
