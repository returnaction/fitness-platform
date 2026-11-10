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
import io.github.returnaction.fitness.identity.registration.entity.role.RoleCode;
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
@SpringBootTest(properties = "identity.registration.login-rate-capacity=1000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationTokenHttpIntegrationTest {
    // Тестируем HTTP и БД без запуска фоновых задач и реальных gRPC-соединений.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.grpc.AccountsProvisioningClient accountsClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.grpc.CoinsProvisioningClient coinsClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.worker.AccountsProvisioningScheduler accountsScheduler;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.worker.CoinsProvisioningScheduler coinsScheduler;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.session.grpc.SecureSessionGrpcServer sessionGrpcServer;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static Path temporaryDirectory;
    private static Path privateKey;
    private static Path publicKey;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Autowired
    private io.github.returnaction.fitness.identity.session.service.RefreshTokenService refreshService;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccessTokenSigner signer;

    @Autowired
    private io.github.returnaction.fitness.identity.security.jwt.AccessTokenVerifier accessVerifier;

    @Autowired
    private io.github.returnaction.fitness.identity.session.repository.SessionVerificationRepository sessions;

    @Autowired
    private io.github.returnaction.fitness.identity.session.grpc.SessionVerificationGrpcService sessionGrpc;

    @Autowired
    private RegistrationService registration;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository userRoles;

    @Autowired
    private io.github.returnaction.fitness.identity.registration.service.RegistrationSessionService registrationSessions;

    @Autowired
    private io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository leases;

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
    void logoutAllRejectsAccessTokenThatExpiresWhileWaitingForLock() throws Exception {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        createActor(user, session, "USER");
        Instant expiresAt = Instant.now().plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String token = signer.sign(user, session, expiresAt, java.util.List.of(RoleCode.USER));
        try (var connection = POSTGRES.createConnection("");
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                statement.setObject(1, user);
                statement.executeQuery().close();
            }
            var result = executor.submit(() -> mvc.perform(post("/api/v1/auth/logout-all")
                    .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
            awaitBlockedConnections(1);
            long waitMillis = java.time.Duration.between(Instant.now(), expiresAt).toMillis();
            if (waitMillis > 0)
                Thread.sleep(waitMillis + 100);
            connection.commit();
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(401);
            assertThat(sessions.check(user, session).active()).isTrue();
        }
    }

    @Test
    void grpcCheckSessionReportsRevocationAfterLogoutAll() throws Exception {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        createActor(user, session, "USER");
        String token = signer.sign(user, session, Instant.now().plusSeconds(3600), java.util.List.of(RoleCode.USER));
        var server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
                .addService(sessionGrpc)
                .build()
                .start();
        var channel = io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
        try {
            var client = io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            var request = io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setUserId(user.toString())
                    .setSessionId(session.toString())
                    .build();
            assertThat(client.checkSession(request).getStatus()).isEqualTo(
                    io.github.returnaction.fitness.contracts.identity.session.v1.SessionStatus.SESSION_STATUS_ACTIVE);
            mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
            assertThat(client.checkSession(request).getStatus()).isEqualTo(
                    io.github.returnaction.fitness.contracts.identity.session.v1.SessionStatus.SESSION_STATUS_INVALID);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void logoutAllRejectsInvalidAndExpiredTokens() throws Exception {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        createActor(user, session, "USER");
        String valid = signer.sign(user, session, Instant.now().plusSeconds(3600), java.util.List.of(RoleCode.USER));
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + valid + "x"))
                .andExpect(status().isUnauthorized());
        String encoded = Files.readString(privateKey)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var key = (java.security.interfaces.ECPrivateKey) java.security.KeyFactory.getInstance("EC")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        Instant past = Instant.now().minusSeconds(1800);
        var expiredSigner = new AccessTokenSigner(key, "test-key", "https://identity.test", "gateway",
                java.time.Clock.fixed(past, java.time.ZoneOffset.UTC));
        String expired = expiredSigner.sign(user, session, past.plusSeconds(900), java.util.List.of(RoleCode.USER));
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        assertThat(sessions.check(user, session).active()).isTrue();
    }

    @Test
    void activationAndRegistrationSessionDoNotDeadlock() throws Exception {
        String email = "activation-race-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        UUID owner = UUID.randomUUID();
        jdbc.sql("UPDATE registration_operations SET profile_ready=true, lease_owner=:owner, "
                + "lease_version=1, lease_until=clock_timestamp()+interval '1 minute' WHERE id=:id")
                .param("owner", owner)
                .param("id", operation.operationId())
                .update();
        var lease = new io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository.Lease(
                operation.operationId(), operation.userId(), owner, 1);
        try (var connection = POSTGRES.createConnection("");
                var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try (var statement = connection
                    .prepareStatement("SELECT id FROM registration_operations WHERE id=? FOR UPDATE")) {
                statement.setObject(1, operation.operationId());
                statement.executeQuery().close();
            }
            var activate = executor.submit(() -> leases.markWalletReady(lease));
            awaitBlockedConnections(1);
            var issue = executor.submit(() -> registrationSessions.create(operation.operationId(), "a"));
            awaitBlockedConnections(2);
            connection.commit();
            assertThat(activate.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(issue.get(20, TimeUnit.SECONDS).userId()).isEqualTo(operation.userId());
        }
    }

    @Test
    void logoutAllAndRegistrationSessionRespectUserLockOrder() throws Exception {
        for (boolean logoutFirst : java.util.List.of(true, false)) {
            String email = "all-register-race-" + UUID.randomUUID() + "@example.com";
            var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
            jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
            jdbc.sql("UPDATE registration_operations SET profile_ready=true, wallet_ready=true, status='COMPLETED', "
                    + "completed_at=clock_timestamp() WHERE id=:id").param("id", operation.operationId()).update();
            String body = "{\"email\":\"" + email + "\",\"password\":\"a\"}";
            var mapper = JsonMapper.builder().build();
            var login = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String access = login.get("access_token").asString();
            try (var connection = POSTGRES.createConnection("");
                    var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, operation.userId());
                    statement.executeQuery().close();
                }
                java.util.concurrent.Callable<Object> logout = () -> mvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", "Bearer " + access)).andReturn();
                java.util.concurrent.Callable<Object> issue = () -> registrationSessions.create(operation.operationId(),
                        "a");
                var first = executor.submit(logoutFirst ? logout : issue);
                awaitBlockedConnections(1);
                var second = executor.submit(logoutFirst ? issue : logout);
                awaitBlockedConnections(2);
                connection.commit();
                Object firstResult = first.get(20, TimeUnit.SECONDS);
                Object secondResult = second.get(20, TimeUnit.SECONDS);
                var logoutResult = (org.springframework.test.web.servlet.MvcResult) (logoutFirst
                        ? firstResult
                        : secondResult);
                var issued = (io.github.returnaction.fitness.identity.registration.service.RegistrationSessionService.IssuedSession) (logoutFirst
                        ? secondResult
                        : firstResult);
                assertThat(logoutResult.getResponse().getStatus()).isEqualTo(204);
                assertThat(jdbc.sql("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=:id")
                        .param("id", issued.sessionId())
                        .query(Boolean.class)
                        .single()).isEqualTo(logoutFirst);
            }
        }
    }

    @Test
    void logoutAllAndLoginRespectUserLockOrder() throws Exception {
        for (boolean logoutFirst : java.util.List.of(true, false)) {
            String email = "all-login-race-" + UUID.randomUUID() + "@example.com";
            var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
            jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
            String body = "{\"email\":\"" + email + "\",\"password\":\"a\"}";
            var mapper = JsonMapper.builder().build();
            var oldLogin = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String access = oldLogin.get("access_token").asString();
            try (var connection = POSTGRES.createConnection("");
                    var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, operation.userId());
                    statement.executeQuery().close();
                }
                java.util.concurrent.Callable<org.springframework.test.web.servlet.MvcResult> logout = () -> mvc
                        .perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + access))
                        .andReturn();
                java.util.concurrent.Callable<org.springframework.test.web.servlet.MvcResult> login = () -> mvc
                        .perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                        .andReturn();
                var first = executor.submit(logoutFirst ? logout : login);
                awaitBlockedConnections(1);
                var second = executor.submit(logoutFirst ? login : logout);
                awaitBlockedConnections(2);
                connection.commit();
                var firstResult = first.get(20, TimeUnit.SECONDS);
                var secondResult = second.get(20, TimeUnit.SECONDS);
                var logoutResult = logoutFirst ? firstResult : secondResult;
                var loginResult = logoutFirst ? secondResult : firstResult;
                assertThat(logoutResult.getResponse().getStatus()).isEqualTo(204);
                assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
                String newAccess = mapper.readTree(loginResult.getResponse().getContentAsString())
                        .get("access_token")
                        .asString();
                UUID newSession = UUID.fromString(SignedJWT.parse(newAccess).getJWTClaimsSet().getStringClaim("sid"));
                assertThat(jdbc.sql("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=:id")
                        .param("id", newSession)
                        .query(Boolean.class)
                        .single()).isEqualTo(logoutFirst);
            }
        }
    }

    private void awaitBlockedConnections(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            int blocked = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND pid<>pg_backend_pid()")
                    .query(Integer.class)
                    .single();
            if (blocked >= expected)
                return;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected waiting database transactions: " + expected);
    }

    @Test
    void simultaneousRefreshAndLogoutEndsWithRevokedSession() throws Exception {
        String email = "logout-race-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        String body = "{\"email\":\"" + email + "\",\"password\":\"a\"}";
        var mapper = JsonMapper.builder().build();
        var login = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String refresh = login.get("refresh_token").asString();
        UUID session = UUID.fromString(SignedJWT.parse(login.get("access_token").asString())
                .getJWTClaimsSet()
                .getStringClaim("sid"));
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var logout = executor.submit(() -> {
                gate.await();
                return mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
            var rotate = executor.submit(() -> {
                gate.await();
                return mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
            gate.countDown();
            assertThat(logout.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(rotate.get(15, TimeUnit.SECONDS)).isIn(200, 401);
        }
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id=:id")
                .param("id", session)
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void simultaneousLogoutAllAllowsOnlyOneSuccess() throws Exception {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        createActor(user, session, "USER");
        String token = signer.sign(user, session, Instant.now().plusSeconds(3600), java.util.List.of(RoleCode.USER));
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> call = () -> {
                gate.await();
                return mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + token))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            gate.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 401);
        }
    }

    @Test
    void unsupportedAcceptDoesNotRevokeSession() throws Exception {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        createActor(user, session, "USER");
        String token = signer.sign(user, session, Instant.now().plusSeconds(3600), java.util.List.of(RoleCode.USER));
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + token)
                .accept("application/xml")).andExpect(status().isNotAcceptable());
        assertThat(jdbc.sql("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=:id")
                .param("id", session)
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void logoutAndLogoutAllRevokeOnlyIntendedSessions() throws Exception {
        String email = "logout-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        UUID otherUser = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        createActor(otherUser, otherSession, "USER");
        String loginBody = "{\"email\":\"" + email + "\",\"password\":\"a\"}";
        var mapper = JsonMapper.builder().build();
        var first = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content(loginBody)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var second = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content(loginBody)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String oldRefresh = first.get("refresh_token").asString();
        String secondRefresh = second.get("refresh_token").asString();
        var rotated = mapper.readTree(mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String rotatedRefresh = rotated.get("refresh_token").asString();
        String wrong = oldRefresh.substring(0, 37) + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + wrong + "\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM auth_sessions WHERE user_id=:id AND revoked_at IS NULL")
                .param("id", operation.userId())
                .query(Integer.class)
                .single()).isEqualTo(2);
        mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + oldRefresh + "\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + oldRefresh + "\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + rotatedRefresh + "\"}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM auth_sessions WHERE user_id=:id AND revoked_at IS NULL")
                .param("id", operation.userId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        String access = second.get("access_token").asString();
        var identity = accessVerifier.verify(access);
        assertThat(sessions.check(identity.userId(), identity.sessionId()).active()).isTrue();
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());
        assertThat(sessions.check(otherUser, otherSession).active()).isTrue();
        assertThat(sessions.check(identity.userId(), identity.sessionId()).active()).isFalse();
        assertThat(accessVerifier.verify(access)).isEqualTo(identity);
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + secondRefresh + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void loginBeanValidationPreservesAllCredentialErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"a@@b\",\"password\":null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.details[0].field").value("email"))
                .andExpect(jsonPath("$.error.details[0].code").value("invalid_format"))
                .andExpect(jsonPath("$.error.details[1].field").value("password"))
                .andExpect(jsonPath("$.error.details[1].code").value("required"));
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details[0].code").value("required"))
                .andExpect(jsonPath("$.error.details[1].code").value("required"));
    }

    @Test
    void loginNormalizesEmailAndKeepsValidationErrors() throws Exception {
        String email = "login-normalization-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"  " + email.toUpperCase(java.util.Locale.ROOT)
                        + "  \",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"a@@b\",\"password\":\"a\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
    }

    @Test
    void loginAndRefreshFailuresKeepTheirHttpContract() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/auth/login").header("X-Request-Id", requestId)
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\",\"password\":\"a\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("invalid_credentials"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.error.request_id").value(requestId));
        mvc.perform(post("/api/v1/auth/refresh").header("X-Request-Id", requestId)
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"invalid\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("invalid_refresh_token"))
                .andExpect(jsonPath("$.error.message").value("Request could not be completed."))
                .andExpect(jsonPath("$.error.request_id").value(requestId));
    }

    @Test
    void unavailableAccountReturnsTheOriginalLoginAndRefreshErrors() throws Exception {
        String email = "unavailable-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        String credentials = "{\"email\":\"" + email + "\",\"password\":\"a\"}";
        String login = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String refresh = JsonMapper.builder().build().readTree(login).get("refresh_token").asString();
        String requestId = UUID.randomUUID().toString();
        jdbc.sql("UPDATE users SET status='SUSPENDED' WHERE id=:id").param("id", operation.userId()).update();

        mvc.perform(post("/api/v1/auth/login").header("X-Request-Id", requestId)
                .contentType(APPLICATION_JSON)
                .content(credentials))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("account_unavailable"))
                .andExpect(jsonPath("$.error.message").value("Account is unavailable."))
                .andExpect(jsonPath("$.error.request_id").value(requestId));
        mvc.perform(post("/api/v1/auth/refresh").header("X-Request-Id", requestId)
                .contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("account_unavailable"))
                .andExpect(jsonPath("$.error.message").value("Request could not be completed."))
                .andExpect(jsonPath("$.error.request_id").value(requestId));
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
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("registration_pending"))
                .andExpect(jsonPath("$.error.message").value("Registration is being prepared. Try again later."))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty())
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
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("USER");
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
        String adminJwt = signer.sign(admin, adminSession, Instant.now().plusSeconds(3600),
                java.util.List.of(RoleCode.USER));
        String regularJwt = signer.sign(regular, regularSession, Instant.now().plusSeconds(3600),
                java.util.List.of(RoleCode.USER));
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
    void roleRemovalCommittedBeforeRefreshIsVisibleInTheNewToken() throws Exception {
        String email = "roles-first-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        jdbc.sql("INSERT INTO user_roles (user_id,role_id) SELECT :id,id FROM roles WHERE code='ADMIN'")
                .param("id", operation.userId())
                .update();
        var mapper = JsonMapper.builder().build();
        String login = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String refresh = mapper.readTree(login).get("refresh_token").asString();
        try (var connection = POSTGRES.createConnection("");
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                lock.setObject(1, operation.userId());
                lock.executeQuery().close();
            }
            try (var delete = connection.prepareStatement(
                    "DELETE FROM user_roles WHERE user_id=? AND role_id=(SELECT id FROM roles WHERE code='ADMIN')")) {
                delete.setObject(1, operation.userId());
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            var pending = executor.submit(() -> mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + refresh + "\"}")).andReturn());
            awaitBlockedConnections(1);
            connection.commit();
            var response = pending.get(20, TimeUnit.SECONDS).getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            String access = mapper.readTree(response.getContentAsString()).get("access_token").asString();
            assertThat(SignedJWT.parse(access).getJWTClaimsSet().getStringListClaim("roles")).containsExactly("USER");
        }
    }

    @Test
    void roleRemovalWaitsForRefreshThatAlreadyHoldsTheUserLock() throws Exception {
        String email = "refresh-first-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        jdbc.sql("INSERT INTO user_roles (user_id,role_id) SELECT :id,id FROM roles WHERE code='ADMIN'")
                .param("id", operation.userId())
                .update();
        var mapper = JsonMapper.builder().build();
        String login = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String refresh = mapper.readTree(login).get("refresh_token").asString();
        var signed = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.execute(status -> {
                var result = refreshService.refresh(refresh);
                signed.countDown();
                try {
                    if (!finish.await(20, TimeUnit.SECONDS))
                        throw new IllegalStateException("refresh timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return result;
            }));
            try {
                assertThat(signed.await(20, TimeUnit.SECONDS)).isTrue();
                var removal = executor.submit(() -> {
                    try (var connection = POSTGRES.createConnection("")) {
                        connection.setAutoCommit(false);
                        try (var lock = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                            lock.setObject(1, operation.userId());
                            lock.executeQuery().close();
                        }
                        try (var delete = connection.prepareStatement(
                                "DELETE FROM user_roles WHERE user_id=? AND role_id=(SELECT id FROM roles WHERE code='ADMIN')")) {
                            delete.setObject(1, operation.userId());
                            assertThat(delete.executeUpdate()).isEqualTo(1);
                        }
                        connection.commit();
                    }
                    return null;
                });
                awaitBlockedConnections(1);
                finish.countDown();
                var issued = first.get(20, TimeUnit.SECONDS);
                removal.get(20, TimeUnit.SECONDS);
                assertThat(SignedJWT.parse(issued.access()).getJWTClaimsSet().getStringListClaim("roles"))
                        .containsExactlyInAnyOrder("USER", "ADMIN");
                String later = mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + issued.refresh() + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
                assertThat(SignedJWT.parse(mapper.readTree(later).get("access_token").asString())
                        .getJWTClaimsSet()
                        .getStringListClaim("roles")).containsExactly("USER");
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void databaseFailureWhileReadingRolesReturns503AndDoesNotRotateRefresh() throws Exception {
        String email = "roles-unavailable-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        var mapper = JsonMapper.builder().build();
        String login = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String refresh = mapper.readTree(login).get("refresh_token").asString();
        String body = "{\"refresh_token\":\"" + refresh + "\"}";
        org.mockito.Mockito
                .doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated database failure"))
                .when(userRoles)
                .findRoleCodesByUserId(operation.userId());
        try {
            mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("identity_unavailable"));
            assertThat(jdbc.sql("SELECT count(*) FROM auth_sessions WHERE user_id=:id")
                    .param("id", operation.userId())
                    .query(Integer.class)
                    .single()).isEqualTo(1);
            String failure = mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("identity_unavailable"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(failure).doesNotContain(refresh, "access_token", "refresh_token");
        } finally {
            org.mockito.Mockito.reset(userRoles);
        }
        mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithoutAssignedRolesFailsWithoutRotatingTheToken() throws Exception {
        String email = "missing-role-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        var mapper = JsonMapper.builder().build();
        String login = mvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String refresh = mapper.readTree(login).get("refresh_token").asString();
        jdbc.sql("DELETE FROM user_roles WHERE user_id=:id").param("id", operation.userId()).update();
        String body = "{\"refresh_token\":\"" + refresh + "\"}";
        mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"));
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) SELECT :id, id FROM roles WHERE code='USER'")
                .param("id", operation.userId())
                .update();
        mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void expiredRefreshIsRejectedWithoutRevokingSession() throws Exception {
        String email = "expired-refresh-" + UUID.randomUUID() + "@example.com";
        var operation = registration.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=:id").param("id", operation.userId()).update();
        String login = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonMapper.builder().build().readTree(login).get("refresh_token").asString();
        UUID tokenId = UUID.fromString(token.substring(0, 36));
        jdbc.sql("UPDATE refresh_tokens SET created_at=clock_timestamp()-interval '2 days', "
                + "expires_at=clock_timestamp()-interval '1 day' WHERE id=:id").param("id", tokenId).update();

        mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("invalid_refresh_token"));
        assertThat(jdbc.sql("SELECT used_at IS NULL AND revoked_at IS NULL FROM refresh_tokens WHERE id=:id")
                .param("id", tokenId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(jdbc.sql("SELECT s.revoked_at IS NULL FROM auth_sessions s "
                + "JOIN refresh_tokens t ON t.session_id=s.id WHERE t.id=:id")
                .param("id", tokenId)
                .query(Boolean.class)
                .single()).isTrue();
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
        assertThat(SignedJWT.parse(original.get("access_token").asString())
                .getJWTClaimsSet()
                .getStringListClaim("roles")).containsExactly("USER");
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) SELECT :user, id FROM roles WHERE code = 'ADMIN'")
                .param("user", operation.userId())
                .update();
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
        assertThat(SignedJWT.parse(fresh.get("access_token").asString())
                .getJWTClaimsSet()
                .getStringListClaim("roles")).containsExactlyInAnyOrder("USER", "ADMIN");
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
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("invalid_refresh_token"))
                .andExpect(jsonPath("$.error.message").value("Request could not be completed."))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
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
