package io.github.returnaction.fitness.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationTokenHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static Path temporaryDirectory;
    private static Path privateKey;
    private static Path publicKey;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        temporaryDirectory = Files.createTempDirectory("fitness-jwt-test-");
        privateKey = temporaryDirectory.resolve("private.pem");
        publicKey = temporaryDirectory.resolve("public.pem");
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("identity.jwt.enabled", () -> "true");
        registry.add("identity.jwt.private-key", () -> privateKey.toString());
        registry.add("identity.jwt.public-key", () -> publicKey.toString());
        registry.add("identity.jwt.key-id", () -> "test-key");
        registry.add("identity.jwt.issuer", () -> "https://identity.test");
        registry.add("identity.jwt.audience", () -> "gateway");
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded) + "\n-----END " + type
                + "-----\n";
    }

    @AfterAll
    static void removeKeys() throws Exception {
        if (privateKey != null) Files.deleteIfExists(privateKey);
        if (publicKey != null) Files.deleteIfExists(publicKey);
        if (temporaryDirectory != null) Files.deleteIfExists(temporaryDirectory);
    }

    @Test
    void registrationReturnsVerifiableTokensOnlyAfterProvisioning() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"email\":\"jwt-http@example.com\",\"password\":\"a\"}";
        mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable());
        jdbc.sql("UPDATE registration_operations SET profile_ready = TRUE, wallet_ready = TRUE, "
                        + "status = 'COMPLETED', completed_at = clock_timestamp() WHERE user_id = "
                        + "(SELECT id FROM users WHERE email_canonical = 'jwt-http@example.com')")
                .update();
        jdbc.sql("UPDATE users SET status = 'ACTIVE' WHERE email_canonical = 'jwt-http@example.com'")
                .update();
        String content = mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var response = JsonMapper.builder().build().readTree(content);
        var jwt = SignedJWT.parse(response.get("access_token").asString());
        String jwks = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var publicJwk = JWKSet.parse(jwks).getKeyByKeyId("test-key").toECKey();
        assertThat(publicJwk.isPrivate()).isFalse();
        assertThat(jwt.verify(new ECDSAVerifier(publicJwk))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("https://identity.test");
        assertThat(response.get("refresh_token").asString()).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }
}
