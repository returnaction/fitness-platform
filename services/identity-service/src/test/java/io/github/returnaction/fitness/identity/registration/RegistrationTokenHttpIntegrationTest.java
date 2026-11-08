package io.github.returnaction.fitness.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.service.RegistrationService;
import io.github.returnaction.fitness.identity.security.jwt.AccessTokenSigner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private AccessTokenSigner signer;

    @Autowired
    private RegistrationService registration;

    @Value("${identity.registration.response-wait-ms}")
    private long responseWaitMillis;

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
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded) + "\n-----END " + type
                + "-----\n";
    }

    @AfterAll
    static void removeKeys() throws Exception {
        if (privateKey != null)
            Files.deleteIfExists(privateKey);
        if (publicKey != null)
            Files.deleteIfExists(publicKey);
        if (temporaryDirectory != null)
            Files.deleteIfExists(temporaryDirectory);
    }

    @Test
    void pendingAccountCannotObtainTokensThroughRegistrationLoginOrRefresh() throws Exception {
        String email = "pending-token-" + UUID.randomUUID() + "@example.com";
        UUID key = UUID.randomUUID();
        String body = "{\"email\":\"" + email + "\",\"password\":\"a\"}";
        var operation = registration.start(key, new RegisterUserRequest(email, "a"));
        String registrationResponse = mvc.perform(post("/api/v1/auth/register")
                .header("Idempotency-Key", key.toString())
                .contentType(APPLICATION_JSON)
                .content(body))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String loginResponse = mvc.perform(
                post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(registrationResponse).doesNotContain("access_token", "refresh_token");
        assertThat(loginResponse).doesNotContain("access_token", "refresh_token");
        assertThat(jdbc.sql("SELECT count(*) FROM auth_sessions WHERE user_id=:id")
                .param("id", operation.userId())
                .query(Integer.class)
                .single())
                .isZero();

        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        String activeLogin = mvc.perform(
                post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String refresh = JsonMapper.builder()
                .build()
                .readTree(activeLogin)
                .get("refresh_token")
                .asString();
        jdbc.sql("UPDATE users SET status='PENDING' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        String refreshResponse = mvc.perform(post("/api/v1/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(refreshResponse).doesNotContain("access_token", "refresh_token", refresh);
    }

    @Test
    void registrationReturnsVerifiableTokensOnlyAfterProvisioning() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"email\":\" JWT-HTTP@example.com \",\"password\":\"a\"}";
        long started = System.nanoTime();
        mvc.perform(post("/api/v1/auth/register")
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(body))
                .andExpect(status().isServiceUnavailable());
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(responseWaitMillis);
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
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.get("user").size()).isEqualTo(3);
        assertThat(response.get("user").get("email").asString()).isEqualTo("jwt-http@example.com");
        assertThat(response.get("user").get("status").asString()).isEqualTo("ACTIVE");
        var session = response.get("session");
        assertThat(session.size()).isEqualTo(5);
        assertThat(session.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(session.get("expires_in").asInt()).isEqualTo(900);
        assertThat(session.get("refresh_expires_in").asInt()).isEqualTo(2592000);
        var jwt = SignedJWT.parse(session.get("access_token").asString());
        String jwks = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var publicJwk = JWKSet.parse(jwks).getKeyByKeyId("test-key").toECKey();
        assertThat(publicJwk.isPrivate()).isFalse();
        assertThat(jwt.verify(new ECDSAVerifier(publicJwk))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("https://identity.test");
        assertThat(session.get("refresh_token").asString()).isNotBlank();
        assertThat(response.get("user").get("id").asString())
                .isEqualTo(jwt.getJWTClaimsSet().getSubject());
        assertThat(jdbc.sql("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL AND id=:id")
                .param("id", UUID.fromString(jwt.getJWTClaimsSet().getStringClaim("sid")))
                .query(Integer.class)
                .single())
                .isEqualTo(1);
    }

    @Test
    void firstRequestWaitsForProvisioningAndReturnsCreated() throws Exception {
        String email = "wait-" + UUID.randomUUID() + "@example.com";
        UUID key = UUID.randomUUID();
        var operation = registration.start(key, new RegisterUserRequest(email, "a"));
        var request = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", key.toString())
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                        .andReturn();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        Thread.sleep(1000);
        assertThat(request).isNotDone();
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        jdbc.sql("UPDATE registration_operations SET profile_ready=TRUE, wallet_ready=TRUE, "
                + "status='COMPLETED', completed_at=clock_timestamp() WHERE id=:id")
                .param("id", operation.operationId())
                .update();
        assertThat(request.get(responseWaitMillis + 5000, TimeUnit.MILLISECONDS)
                .getResponse()
                .getStatus())
                .isEqualTo(201);
    }

    @Test
    void recoveryRequiresActiveSuperAdminAndRecordsActor() throws Exception {
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest("blocked@example.com", "a"));
        jdbc.sql("UPDATE registration_operations SET recovery_blocked_at=clock_timestamp(), "
                + "last_error_code='COINS_PERMISSION_DENIED' WHERE id=:id")
                .param("id", operation.operationId())
                .update();
        UUID admin = UUID.randomUUID();
        UUID regular = UUID.randomUUID();
        UUID adminSession = UUID.randomUUID();
        UUID regularSession = UUID.randomUUID();
        createActor(admin, adminSession, "SUPER_ADMIN");
        createActor(regular, regularSession, "USER");
        String adminJwt = signer.sign(admin, adminSession, Instant.now().plusSeconds(3600));
        String regularJwt = signer.sign(regular, regularSession, Instant.now().plusSeconds(3600));
        String path = "/api/v1/admin/identity/registration-operations/" + operation.operationId();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + adminJwt + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + regularJwt)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.operation_id").value(operation.operationId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.recovery_blocked_at").isNotEmpty())
                .andExpect(jsonPath("$.last_error_code").value("COINS_PERMISSION_DENIED"));
        mvc.perform(get("/api/v1/admin/identity/registration-operations/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/identity/registration-operations/not-a-uuid")
                .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path + "/resume")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(APPLICATION_JSON)
                .content("{\"expected_version\":1,\"reason\":\"Fixed access\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.operation_id").value(operation.operationId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(path + "/resume")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(APPLICATION_JSON)
                .content("{\"expected_version\":1,\"reason\":\"Fixed access\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.sql(
                "SELECT count(*) FROM registration_recovery_audit WHERE operation_id=:id AND actor_user_id=:actor")
                .param("id", operation.operationId())
                .param("actor", admin)
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        jdbc.sql("UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE id=:id")
                .param("id", adminSession)
                .update();
        mvc.perform(get(path).header("Authorization", "Bearer " + adminJwt)).andExpect(status().isUnauthorized());
    }

    private void createActor(UUID user, UUID session, String role) {
        jdbc.sql("INSERT INTO users (id,email_canonical,status) VALUES (:id,:email,'ACTIVE')")
                .param("id", user)
                .param("email", user + "@example.com")
                .update();
        jdbc.sql(
                "INSERT INTO auth_sessions (id,user_id,expires_at) VALUES (:id,:user,clock_timestamp()+interval '1 day')")
                .param("id", session)
                .param("user", user)
                .update();
        jdbc.sql("INSERT INTO user_roles (user_id,role_id) SELECT :user,id FROM roles WHERE code=:role")
                .param("user", user)
                .param("role", role)
                .update();
    }

    @Test
    void refreshRotatesAndCorrectReplayRevokesButWrongSecretDoesNot() throws Exception {
        String email = "refresh-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        var mapper = JsonMapper.builder().build();
        String login = mvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var original = mapper.readTree(login);
        String oldToken = original.get("refresh_token").asString();
        UUID sessionId = UUID.fromString(SignedJWT.parse(original.get("access_token").asString())
                .getJWTClaimsSet()
                .getStringClaim("sid"));
        String wrong = oldToken.substring(0, 37) + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        mvc.perform(post("/api/v1/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + wrong + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=:id")
                .param("id", sessionId)
                .query(Boolean.class)
                .single())
                .isTrue();
        String rotated = mvc.perform(post("/api/v1/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + oldToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var fresh = mapper.readTree(rotated);
        String newToken = fresh.get("refresh_token").asString();
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(SignedJWT.parse(fresh.get("access_token").asString())
                .getJWTClaimsSet()
                .getStringClaim("sid"))
                .isEqualTo(sessionId.toString());
        assertThat(fresh.get("refresh_expires_in").asLong()).isBetween(1L, 2592000L);
        assertThat(jdbc.sql(
                "SELECT used_at IS NOT NULL AND replaced_by_token_id IS NOT NULL FROM refresh_tokens WHERE id=:id")
                .param("id", UUID.fromString(oldToken.substring(0, 36)))
                .query(Boolean.class)
                .single())
                .isTrue();
        mvc.perform(post("/api/v1/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + wrong + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=:id")
                .param("id", sessionId)
                .query(Boolean.class)
                .single())
                .isTrue();
        mvc.perform(post("/api/v1/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + oldToken + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id=:id")
                .param("id", sessionId)
                .query(Boolean.class)
                .single())
                .isTrue();
        mvc.perform(post("/api/v1/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + newToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void simultaneousRefreshesHaveOneSuccessAndRevokeSession() throws Exception {
        String email = "parallel-refresh-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        var mapper = JsonMapper.builder().build();
        var login = mvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var response = mapper.readTree(login);
        String token = response.get("refresh_token").asString();
        UUID sessionId = UUID.fromString(SignedJWT.parse(response.get("access_token").asString())
                .getJWTClaimsSet()
                .getStringClaim("sid"));
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> call = () -> {
                gate.await();
                return mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + token + "\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            var one = executor.submit(call);
            var two = executor.submit(call);
            gate.countDown();
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 401);
        }
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id=:id")
                .param("id", sessionId)
                .query(Boolean.class)
                .single())
                .isTrue();
    }

    @Test
    void activeLoginCreatesIndependentSessionsAndRejectsWrongCredentials() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE registration_operations SET profile_ready=true, wallet_ready=true, status='COMPLETED', "
                + "completed_at=clock_timestamp() WHERE id=:id")
                .param("id", operation.operationId())
                .update();
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        String oldHash = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 8192, 1)
                .encode("a");
        jdbc.sql("UPDATE user_credentials SET password_hash=:hash WHERE user_id=:id")
                .param("hash", oldHash)
                .param("id", operation.userId())
                .update();
        String body = "{\"email\":\"" + email + "\",\"password\":\"a\"}";
        mvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                gate.await();
                return mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
            });
            var second = executor.submit(() -> {
                gate.await();
                return mvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
            });
            gate.countDown();
            var mapper = JsonMapper.builder().build();
            var one = mapper.readTree(first.get());
            var two = mapper.readTree(second.get());
            assertThat(one.get("refresh_token").asString())
                    .isNotEqualTo(two.get("refresh_token").asString());
            var jwt = SignedJWT.parse(one.get("access_token").asString());
            assertThat(jwt.getJWTClaimsSet().getSubject())
                    .isEqualTo(operation.userId().toString());
        }
        assertThat(jdbc.sql(
                "SELECT count(*) FROM auth_sessions WHERE user_id=:id AND registration_operation_id IS NULL AND revoked_at IS NULL")
                .param("id", operation.userId())
                .query(Integer.class)
                .single())
                .isEqualTo(2);
        String newHash = jdbc.sql("SELECT password_hash FROM user_credentials WHERE user_id=:id")
                .param("id", operation.userId())
                .query(String.class)
                .single();
        assertThat(newHash).isNotEqualTo(oldHash);
        jdbc.sql("UPDATE users SET status='SUSPENDED' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM auth_sessions WHERE user_id=:id")
                .param("id", operation.userId())
                .query(Integer.class)
                .single())
                .isEqualTo(2);
    }
}
