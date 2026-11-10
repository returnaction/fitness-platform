package io.github.returnaction.fitness.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.exception.RecoveryConflictException;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationNotReadyException;
import io.github.returnaction.fitness.identity.registration.grpc.AccountsProvisioningClient;
import io.github.returnaction.fitness.identity.registration.grpc.CoinsProvisioningClient;
import io.github.returnaction.fitness.identity.registration.model.RegistrationStartResult;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import io.github.returnaction.fitness.identity.registration.service.RegistrationRecoveryService;
import io.github.returnaction.fitness.identity.registration.service.RegistrationService;
import io.github.returnaction.fitness.identity.registration.service.RegistrationSessionService;
import io.github.returnaction.fitness.identity.session.service.SessionTokenService;
import io.github.returnaction.fitness.identity.registration.worker.AccountsProvisioningProcessor;
import io.github.returnaction.fitness.identity.registration.worker.AccountsProvisioningScheduler;
import io.github.returnaction.fitness.identity.registration.worker.CoinsProvisioningProcessor;
import io.github.returnaction.fitness.identity.registration.worker.ProvisioningBatchRunner;
import io.github.returnaction.fitness.identity.security.jwt.AccessTokenSigner;
import io.github.returnaction.fitness.identity.session.repository.SessionVerificationRepository;
import io.github.returnaction.fitness.identity.session.service.RefreshTokenService;
import io.github.returnaction.fitness.identity.shared.exception.LoginCredentialsChangedException;
import io.github.returnaction.fitness.identity.registration.entity.user.UserCredentialEntity;
import io.github.returnaction.fitness.identity.registration.entity.user.UserEntity;
import io.github.returnaction.fitness.identity.registration.entity.user.UserStatus;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationServiceIntegrationTest {
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

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private io.github.returnaction.fitness.identity.session.repository.LoginRepository loginRepository;

    @Autowired
    private RegistrationSessionService sessionService;

    @Autowired
    private io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository userRoleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private io.github.returnaction.fitness.identity.session.repository.RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RegistrationLeaseRepository leases;

    @Autowired
    private SessionVerificationRepository sessionVerification;

    @Autowired
    private RegistrationRecoveryService recovery;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        io.github.returnaction.fitness.identity.TestJwtKeys.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void loginRepositoryFindsUserAndCredentialsDirectlyByEmail() {
        String email = "login-query-" + UUID.randomUUID() + "@example.com";
        var operation = registrationService.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));

        var user = loginRepository.findByEmail(email).orElseThrow();
        assertThat(user.id()).isEqualTo(operation.userId());
        assertThat(user.status()).isEqualTo(UserStatus.PENDING);
        assertThat(passwordEncoder.matches("a", user.passwordHash())).isTrue();
        assertThat(loginRepository.findByEmail("missing-" + UUID.randomUUID() + "@example.com")).isEmpty();
    }

    @Test
    void workerClaimsAndRecordsProfileReadinessWithoutNetworkTransaction() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("worker-" + UUID.randomUUID() + "@example.com", "a"));
        var client = org.mockito.Mockito.mock(AccountsProvisioningClient.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive())
                    .isFalse();
            return null;
        })
                .when(client)
                .ensureProfile(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        var processor = new AccountsProvisioningProcessor(client, leases);
        try (var runner = new ProvisioningBatchRunner(4, 4)) {
            var scheduler = new AccountsProvisioningScheduler(leases, processor, runner, 30);
            for (int attempt = 0; attempt < 10; attempt++) {
                scheduler.poll();
                boolean ready = jdbcClient
                        .sql("SELECT profile_ready FROM registration_operations WHERE id = :id")
                        .param("id", operation.operationId())
                        .query(Boolean.class)
                        .single();
                if (ready) {
                    assertThat(jdbcClient
                            .sql("SELECT status FROM users WHERE id = :id")
                            .param("id", operation.userId())
                            .query(String.class)
                            .single())
                            .isEqualTo("PENDING");
                    return;
                }
            }
        }
        throw new AssertionError("Worker did not mark the profile ready");
    }

    @Test
    void workerProcessesDistinctLeasesConcurrentlyWithoutNetworkTransaction() {
        var operations = new java.util.ArrayList<RegistrationStartResult>();
        for (int index = 0; index < 8; index++) {
            var operation = registrationService.start(
                    UUID.randomUUID(),
                    new RegisterUserRequest("parallel-worker-" + UUID.randomUUID() + "@example.com", "a"));
            operations.add(operation);
            jdbcClient
                    .sql(
                            "UPDATE registration_operations SET next_retry_at=clock_timestamp()-interval '10 days' WHERE id=:id")
                    .param("id", operation.operationId())
                    .update();
        }
        var started = new CountDownLatch(4);
        var users = ConcurrentHashMap.<UUID>newKeySet();
        var client = org.mockito.Mockito.mock(AccountsProvisioningClient.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive())
                    .isFalse();
            users.add(invocation.getArgument(2));
            started.countDown();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        })
                .when(client)
                .ensureProfile(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        try (var runner = new ProvisioningBatchRunner(8, 4)) {
            new AccountsProvisioningScheduler(leases, new AccountsProvisioningProcessor(client, leases), runner, 30)
                    .poll();
        }
        assertThat(users)
                .containsExactlyInAnyOrderElementsOf(
                        operations.stream().map(RegistrationStartResult::userId).toList());
        for (var operation : operations) {
            assertThat(jdbcClient
                    .sql("SELECT profile_ready FROM registration_operations WHERE id=:id")
                    .param("id", operation.operationId())
                    .query(Boolean.class)
                    .single())
                    .isTrue();
        }
    }

    @Test
    void workerRetriesUnavailableAccountsAndEventuallyMarksProfileReady() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("recovery-" + UUID.randomUUID() + "@example.com", "a"));
        var client = org.mockito.Mockito.mock(AccountsProvisioningClient.class);
        org.mockito.Mockito.doThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException())
                .doNothing()
                .when(client)
                .ensureProfile(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(operation.operationId()),
                        org.mockito.ArgumentMatchers.eq(operation.userId()));
        var processor = new AccountsProvisioningProcessor(client, leases);
        var first = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        processor.process(first);

        assertThat(jdbcClient
                .sql("SELECT retry_count = 1 AND NOT profile_ready AND recovery_blocked_at IS NULL "
                        + "AND next_retry_at > clock_timestamp() "
                        + "FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        assertThat(leases.claim(UUID.randomUUID(), 100, 30))
                .noneMatch(lease -> lease.operationId().equals(operation.operationId()));

        jdbcClient
                .sql("UPDATE registration_operations SET next_retry_at = clock_timestamp() WHERE id = :id")
                .param("id", operation.operationId())
                .update();
        var second = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        processor.process(second);
        assertThat(jdbcClient
                .sql("SELECT profile_ready FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        assertThat(userRepository.findById(operation.userId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.PENDING);
    }

    @Test
    void walletWorkerClaimsOnlyAfterProfileAndActivatesUser() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("wallet-" + UUID.randomUUID() + "@example.com", "a"));
        assertThat(leases.claimWallet(UUID.randomUUID(), 100, 30))
                .noneMatch(lease -> lease.operationId().equals(operation.operationId()));
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.markProfileReady(profileLease)).isTrue();
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        var client = org.mockito.Mockito.mock(CoinsProvisioningClient.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive())
                    .isFalse();
            return null;
        })
                .when(client)
                .ensureWallet(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(operation.operationId()),
                        org.mockito.ArgumentMatchers.eq(operation.userId()));
        new CoinsProvisioningProcessor(client, leases).process(walletLease);
        assertThat(jdbcClient
                .sql("SELECT profile_ready AND wallet_ready FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        assertThat(leases.markWalletReady(walletLease)).isFalse();
        assertThat(userRepository.findById(operation.userId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(jdbcClient
                .sql("SELECT status FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(String.class)
                .single())
                .isEqualTo("COMPLETED");
    }

    @Test
    void walletCompletionRollsBackWhenUserCannotBeActivated() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("disabled-" + UUID.randomUUID() + "@example.com", "a"));
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.markProfileReady(profileLease)).isTrue();
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        jdbcClient
                .sql("UPDATE users SET status = 'DISABLED' WHERE id = :id")
                .param("id", operation.userId())
                .update();
        assertThatThrownBy(() -> leases.markWalletReady(walletLease))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbcClient
                .sql(
                        "SELECT status = 'PENDING' AND NOT wallet_ready FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
    }

    @Test
    void registrationSessionRequiresActiveAccountAndCorrectPasswordAndRevokesPriorSession() throws Exception {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("session-" + UUID.randomUUID() + "@example.com", "a"));
        assertThatThrownBy(() -> sessionService.create(operation.operationId(), "a"))
                .isInstanceOf(RegistrationNotReadyException.class);
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.markProfileReady(profileLease);
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.markWalletReady(walletLease);
        assertThatThrownBy(() -> sessionService.create(operation.operationId(), "wrong"))
                .isInstanceOf(RegistrationConflictException.class);
        var first = sessionService.create(operation.operationId(), "a");
        var second = sessionService.create(operation.operationId(), "a");
        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(jdbcClient
                .sql(
                        "SELECT count(*) FROM auth_sessions WHERE registration_operation_id = :id AND revoked_at IS NULL")
                .param("id", operation.operationId())
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        assertThat(jdbcClient
                .sql("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id = :id")
                .param("id", first.sessionId())
                .query(Boolean.class)
                .single())
                .isTrue();
        String[] parts = second.refreshToken().split("\\.");
        assertThat(parts).hasSize(2);
        byte[] secret = java.util.Base64.getUrlDecoder().decode(parts[1]);
        assertThat(secret).hasSize(32);
        byte[] storedHash = jdbcClient
                .sql("SELECT secret_hash FROM refresh_tokens WHERE id = :id")
                .param("id", UUID.fromString(parts[0]))
                .query(byte[].class)
                .single();
        assertThat(storedHash)
                .isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(secret));
    }

    @Test
    void tokenSigningFailureRollsBackSessionAndKeepsPreviousSession() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("tokens-" + UUID.randomUUID() + "@example.com", "a"));
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.markProfileReady(profileLease);
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.markWalletReady(walletLease);
        var previous = sessionService.create(operation.operationId(), "a");
        var signer = org.mockito.Mockito.mock(AccessTokenSigner.class);
        org.mockito.Mockito.when(signer.sign(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("signing failed"));
        var service = new SessionTokenService(sessionService, signer, userRoleRepository);
        assertThatThrownBy(() -> transactionTemplate.execute(status -> service.create(operation.operationId(), "a")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbcClient
                .sql("SELECT count(*) FROM auth_sessions WHERE registration_operation_id = :id")
                .param("id", operation.operationId())
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        assertThat(jdbcClient
                .sql("SELECT revoked_at IS NULL FROM auth_sessions WHERE id = :id")
                .param("id", previous.sessionId())
                .query(Boolean.class)
                .single())
                .isTrue();
    }

    @Test
    void sessionVerificationChecksOwnershipStatusRevocationAndExpiry() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("verify-" + UUID.randomUUID() + "@example.com", "a"));
        UUID session = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO auth_sessions (id,user_id,expires_at) VALUES (:id,:user,clock_timestamp()+interval '1 day')")
                .param("id", session)
                .param("user", operation.userId())
                .update();
        assertThat(sessionVerification.check(operation.userId(), session).active())
                .isFalse();
        jdbcClient
                .sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        var active = sessionVerification.check(operation.userId(), session);
        assertThat(active.active()).isTrue();
        assertThat(active.checkedAt()).isNotNull();
        assertThat(sessionVerification.check(UUID.randomUUID(), session).active())
                .isFalse();
        assertThat(sessionVerification
                .check(operation.userId(), UUID.randomUUID())
                .active())
                .isFalse();
        jdbcClient
                .sql("UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE id=:id")
                .param("id", session)
                .update();
        assertThat(sessionVerification.check(operation.userId(), session).active())
                .isFalse();
        jdbcClient
                .sql("UPDATE auth_sessions SET revoked_at=NULL, created_at=clock_timestamp()-interval '2 days', "
                        + "expires_at=clock_timestamp()-interval '1 day' WHERE id=:id")
                .param("id", session)
                .update();
        assertThat(sessionVerification.check(operation.userId(), session).active())
                .isFalse();
        jdbcClient
                .sql("UPDATE auth_sessions SET expires_at=clock_timestamp()+interval '1 day' WHERE id=:id")
                .param("id", session)
                .update();
        jdbcClient
                .sql("UPDATE users SET status='SUSPENDED' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        assertThat(sessionVerification.check(operation.userId(), session).active())
                .isFalse();
    }

    @Test
    void blockedRegistrationCanBeResumedOnlyOnceWithAudit() throws Exception {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("recover-" + UUID.randomUUID() + "@example.com", "a"));
        var lease = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(item -> item.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.recordFailure(lease, false, "COINS_PERMISSION_DENIED"))
                .isTrue();
        var blocked = recovery.read(operation.operationId());
        assertThat(blocked.recoveryBlockedAt()).isNotNull();
        assertThat(blocked.version()).isGreaterThan(1);
        UUID actor = UUID.randomUUID();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                gate.await();
                try {
                    recovery.resume(operation.operationId(), actor, blocked.version(), "Исправлены права сервиса");
                    return "ok";
                } catch (RecoveryConflictException error) {
                    return error.code();
                }
            });
            var second = executor.submit(() -> {
                gate.await();
                try {
                    recovery.resume(operation.operationId(), actor, blocked.version(), "Исправлены права сервиса");
                    return "ok";
                } catch (RecoveryConflictException error) {
                    return error.code();
                }
            });
            gate.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("ok", "version_conflict");
        }
        var restored = recovery.read(operation.operationId());
        assertThat(restored.recoveryBlockedAt()).isNull();
        assertThat(restored.lastErrorCode()).isNull();
        assertThat(restored.version()).isEqualTo(blocked.version() + 1);
        assertThat(jdbcClient
                .sql("SELECT count(*) FROM registration_recovery_audit WHERE operation_id=:id "
                        + "AND actor_user_id=:actor AND previous_error_code='COINS_PERMISSION_DENIED'")
                .param("id", operation.operationId())
                .param("actor", actor)
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        assertThat(leases.markProfileReady(lease)).isFalse();
        assertThatThrownBy(() -> recovery.resume(operation.operationId(), actor, restored.version(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditFailureRollsBackRecovery() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("audit-" + UUID.randomUUID() + "@example.com", "a"));
        var lease = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(item -> item.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.recordFailure(lease, false, "ACCOUNTS_PERMISSION_DENIED");
        var before = recovery.read(operation.operationId());
        assertThatThrownBy(() -> recovery.resume(
                operation.operationId(), UUID.randomUUID(), before.version(), "invalid\u0000text"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var after = recovery.read(operation.operationId());
        assertThat(after.recoveryBlockedAt()).isEqualTo(before.recoveryBlockedAt());
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.lastErrorCode()).isEqualTo("ACCOUNTS_PERMISSION_DENIED");
    }

    @Test
    void pendingLoginChecksPasswordAndAcceleratesOnlyUnblockedUnleasedOperation() throws Exception {
        String email = "pending-" + UUID.randomUUID() + "@example.com";
        var operation = registrationService.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));
        jdbcClient
                .sql(
                        "UPDATE registration_operations SET next_retry_at=clock_timestamp()+interval '1 hour' WHERE id=:id")
                .param("id", operation.operationId())
                .update();
        var before = jdbcClient
                .sql("SELECT encode(idempotency_key_digest,'hex') FROM registration_operations WHERE id=:id")
                .param("id", operation.operationId())
                .query(String.class)
                .single();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_credentials"));
        org.mockito.Mockito.clearInvocations(passwordEncoder);
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"unknown@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_credentials"));
        org.mockito.Mockito.verify(passwordEncoder)
                .matches(
                        org.mockito.ArgumentMatchers.eq("wrong"), org.mockito.ArgumentMatchers.anyString());
        assertThat(jdbcClient
                .sql(
                        "SELECT next_retry_at > clock_timestamp()+interval '30 minutes' FROM registration_operations WHERE id=:id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("registration_pending"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
        assertThat(jdbcClient
                .sql("SELECT next_retry_at <= clock_timestamp() FROM registration_operations WHERE id=:id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        assertThat(jdbcClient
                .sql("SELECT encode(idempotency_key_digest,'hex') FROM registration_operations WHERE id=:id")
                .param("id", operation.operationId())
                .query(String.class)
                .single())
                .isEqualTo(before);
        jdbcClient
                .sql("UPDATE registration_operations SET next_retry_at=clock_timestamp()+interval '1 hour', "
                        + "recovery_blocked_at=clock_timestamp() WHERE id=:id")
                .param("id", operation.operationId())
                .update();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("registration_pending"));
        assertThat(jdbcClient
                .sql(
                        "SELECT next_retry_at > clock_timestamp()+interval '30 minutes' FROM registration_operations WHERE id=:id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        jdbcClient
                .sql("UPDATE registration_operations SET recovery_blocked_at=NULL, lease_owner=:owner, "
                        + "lease_until=clock_timestamp()+interval '1 minute' WHERE id=:id")
                .param("owner", UUID.randomUUID())
                .param("id", operation.operationId())
                .update();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbcClient
                .sql(
                        "SELECT next_retry_at > clock_timestamp()+interval '30 minutes' FROM registration_operations WHERE id=:id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
    }

    @Test
    void loginSessionRejectsChangedCredentialsAndRollsBackWhenSigningFails() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("login-race-" + UUID.randomUUID() + "@example.com", "a"));
        jdbcClient
                .sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        String hash = jdbcClient
                .sql("SELECT password_hash FROM user_credentials WHERE user_id=:id")
                .param("id", operation.userId())
                .query(String.class)
                .single();
        assertThatThrownBy(() -> sessionService.createLogin(operation.userId(), "stale-hash", null))
                .isInstanceOf(LoginCredentialsChangedException.class);
        var signer = org.mockito.Mockito.mock(AccessTokenSigner.class);
        org.mockito.Mockito.when(signer.sign(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("signing failed"));
        var service = new SessionTokenService(sessionService, signer, userRoleRepository);
        assertThatThrownBy(
                () -> transactionTemplate.execute(status -> service.createLogin(operation.userId(), hash, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbcClient
                .sql("SELECT count(*) FROM auth_sessions WHERE user_id=:id")
                .param("id", operation.userId())
                .query(Integer.class)
                .single())
                .isZero();
        jdbcClient
                .sql("UPDATE users SET status='DISABLED' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        assertThatThrownBy(() -> sessionService.createLogin(operation.userId(), hash, null))
                .isInstanceOf(LoginCredentialsChangedException.class);
    }

    @Test
    void refreshSigningFailureRollsBackAndExpiredSessionIsRejected() {
        var operation = registrationService.start(
                UUID.randomUUID(),
                new RegisterUserRequest("refresh-rollback-" + UUID.randomUUID() + "@example.com", "a"));
        jdbcClient
                .sql("UPDATE users SET status='ACTIVE' WHERE id=:id")
                .param("id", operation.userId())
                .update();
        String hash = jdbcClient
                .sql("SELECT password_hash FROM user_credentials WHERE user_id=:id")
                .param("id", operation.userId())
                .query(String.class)
                .single();
        var session = sessionService.createLogin(operation.userId(), hash, null);
        var signer = org.mockito.Mockito.mock(AccessTokenSigner.class);
        org.mockito.Mockito.when(signer.sign(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("signing failed"));
        var refresh = new RefreshTokenService(refreshTokenRepository, signer, userRoleRepository);
        assertThatThrownBy(() -> transactionTemplate.execute(status -> refresh.refresh(session.refreshToken())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbcClient
                .sql("SELECT count(*) FROM refresh_tokens WHERE session_id=:id AND used_at IS NULL")
                .param("id", session.sessionId())
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        jdbcClient
                .sql("UPDATE auth_sessions SET created_at=clock_timestamp()-interval '2 days', "
                        + "expires_at=clock_timestamp()-interval '1 day' WHERE id=:id")
                .param("id", session.sessionId())
                .update();
        var result = transactionTemplate.execute(status -> refresh.refresh(session.refreshToken()));
        assertThat(result).isNotNull();
        assertThat(result.code()).isEqualTo("invalid_refresh_token");
    }

    @Test
    void retryAndPermanentFailureRespectLeaseAndScheduling() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("retry-" + UUID.randomUUID() + "@example.com", "a"));
        var first = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.recordFailure(first, true, "ACCOUNTS_UNAVAILABLE")).isTrue();
        assertThat(leases.recordFailure(first, true, "ACCOUNTS_UNAVAILABLE")).isFalse();
        assertThat(jdbcClient
                .sql("SELECT retry_count FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        assertThat(jdbcClient
                .sql("SELECT next_retry_at > clock_timestamp() FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        assertThat(leases.claim(UUID.randomUUID(), 100, 30))
                .noneMatch(l -> l.operationId().equals(operation.operationId()));
        jdbcClient
                .sql("UPDATE registration_operations SET next_retry_at = clock_timestamp() WHERE id = :id")
                .param("id", operation.operationId())
                .update();
        var second = leases.claim(UUID.randomUUID(), 100, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.recordFailure(second, false, "ACCOUNTS_PERMISSION_DENIED"))
                .isTrue();
        assertThat(jdbcClient
                .sql("SELECT recovery_blocked_at IS NOT NULL FROM registration_operations WHERE id = :id")
                .param("id", operation.operationId())
                .query(Boolean.class)
                .single())
                .isTrue();
        assertThat(leases.claim(UUID.randomUUID(), 100, 30))
                .noneMatch(l -> l.operationId().equals(operation.operationId()));
    }

    @Test
    void expiredLeaseCannotCompleteAfterAnotherWorkerClaims() {
        var operation = registrationService.start(UUID.randomUUID(),
                new RegisterUserRequest("lease-test@example.com", "a"));
        var first = leases.claim(UUID.randomUUID(), 20, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        jdbcClient
                .sql(
                        "UPDATE registration_operations SET lease_until = clock_timestamp() - interval '1 second' WHERE id = :id")
                .param("id", operation.operationId())
                .update();
        var second = leases.claim(UUID.randomUUID(), 20, 30)
                .stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.markProfileReady(first)).isFalse();
        assertThat(leases.markProfileReady(second)).isTrue();
        assertThat(leases.markProfileReady(second)).isFalse();
    }

    @Test
    void createsPendingUserWithArgon2idHashAndUserRole() {
        UUID idempotencyKey = UUID.randomUUID();
        RegistrationStartResult result = registrationService.start(idempotencyKey,
                new RegisterUserRequest(" Anna@Example.com ", "a"));

        UserEntity user = userRepository.findById(result.userId()).orElseThrow();
        UserCredentialEntity credentials = userCredentialRepository.findById(result.userId()).orElseThrow();

        assertThat(user.getEmailCanonical()).isEqualTo("anna@example.com");
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(credentials.getPasswordHash()).startsWith("$argon2id$");
        assertThat(passwordEncoder.matches("a", credentials.getPasswordHash())).isTrue();
        assertThat(userRoleCount(result.userId())).isEqualTo(1);
        assertThat(registrationService.start(idempotencyKey, new RegisterUserRequest("anna@example.com", "a")))
                .isEqualTo(result);
    }

    @Test
    void createsOnePendingRegistrationThroughHttpAndReturnsRetryableResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        String request = "{\"email\":\"http@example.com\",\"password\":\"a\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.code").value("registration_unavailable"))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/register")
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(request))
                .andExpect(status().isServiceUnavailable());

        assertThat(userRepository.findByEmailCanonical("http@example.com")).isPresent();
        assertThat(userCount("http@example.com")).isEqualTo(1);
    }

    @Test
    void rejectsMissingInvalidOrEmptyRegistrationInput() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));

        mockMvc.perform(post("/api/v1/auth/register")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"invalid@example.com\",\"password\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));

        mockMvc.perform(post("/api/v1/auth/register")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"empty@example.com\",\"password\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.details[0].field").value("password"));
    }

    @Test
    void rejectsNonCanonicalOrWrongVariantIdempotencyKeys() throws Exception {
        long usersBefore = userRepository.count();
        for (String key : List.of("1-1-4000-8000-1", "00000000-0000-4000-c000-000000000000")) {
            mockMvc.perform(post("/api/v1/auth/register")
                    .header("Idempotency-Key", key)
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"key-test@example.com\",\"password\":\"a\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid_request"));
        }
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void acceptsUppercaseCanonicalUuidAndReusesSameOperation() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"email\":\"uppercase-key@example.com\",\"password\":\"a\"}";
        for (String value : List.of(key.toUpperCase(java.util.Locale.ROOT), key)) {
            mockMvc.perform(post("/api/v1/auth/register")
                    .header("Idempotency-Key", value)
                    .contentType(APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isServiceUnavailable());
        }
        assertThat(userCount("uppercase-key@example.com")).isEqualTo(1);
    }

    @Test
    void rejectsMalformedAndNonAsciiEmailWithoutCreatingUsers() throws Exception {
        long usersBefore = userRepository.count();
        for (String email : List.of("a@@b", "a@b@c", "@ab", "ab@", "\u212a@a")) {
            mockMvc.perform(post("/api/v1/auth/register")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("validation_error"))
                    .andExpect(jsonPath("$.error.details[0].field").value("email"));
        }
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void duplicateEmailDatabaseErrorDoesNotExposeEmailInHibernateLog() {
        String email = "privacy-" + UUID.randomUUID() + "@example.com";
        registrationService.start(UUID.randomUUID(), new RegisterUserRequest(email, "a"));

        Logger logger = (Logger) LoggerFactory.getLogger("org.hibernate.orm.jdbc.error");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(
                    () -> userRepository.saveAndFlush(new UserEntity(UUID.randomUUID(), email, UserStatus.PENDING)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("users_email_canonical_unique"))
                    .noneMatch(message -> message.contains(email));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void handlesConcurrentRequestsWithSameKeyWithoutDuplicate() throws Exception {
        UUID key = UUID.randomUUID();
        synchronizePasswordEncoding();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<RegistrationStartResult> first = CompletableFuture.supplyAsync(
                    () -> registrationService.start(
                            key, new RegisterUserRequest("same-key@example.com", "race-password")),
                    executor);
            CompletableFuture<RegistrationStartResult> second = CompletableFuture.supplyAsync(
                    () -> registrationService.start(
                            key, new RegisterUserRequest("same-key@example.com", "race-password")),
                    executor);

            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(userCount("same-key@example.com")).isEqualTo(1);
    }

    @Test
    void handlesConcurrentRequestsWithDifferentKeysAndSameEmail() throws Exception {
        synchronizePasswordEncoding();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<RegistrationStartResult> first = CompletableFuture.supplyAsync(
                    () -> registrationService.start(
                            UUID.randomUUID(), new RegisterUserRequest("same-email@example.com", "race-password")),
                    executor);
            CompletableFuture<RegistrationStartResult> second = CompletableFuture.supplyAsync(
                    () -> registrationService.start(
                            UUID.randomUUID(), new RegisterUserRequest("same-email@example.com", "race-password")),
                    executor);

            List<CompletableFuture<RegistrationStartResult>> results = List.of(first, second);
            int successfulRequests = 0;
            int conflictingRequests = 0;
            for (CompletableFuture<RegistrationStartResult> result : results) {
                try {
                    result.get();
                    successfulRequests++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(RegistrationConflictException.class);
                    conflictingRequests++;
                }
            }
            assertThat(successfulRequests).isEqualTo(1);
            assertThat(conflictingRequests).isEqualTo(1);
        }
        assertThat(userCount("same-email@example.com")).isEqualTo(1);
    }

    private void synchronizePasswordEncoding() {
        var barrier = new CyclicBarrier(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            barrier.await(15, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        })
                .when(passwordEncoder)
                .encode("race-password");
    }

    private int userRoleCount(UUID userId) {
        return jdbcClient
                .sql("SELECT count(*) FROM user_roles user_role JOIN roles role ON role.id = user_role.role_id "
                        + "WHERE user_role.user_id = :userId AND role.code = 'USER'")
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }

    private int userCount(String emailCanonical) {
        return jdbcClient
                .sql("SELECT count(*) FROM users WHERE email_canonical = :emailCanonical")
                .param("emailCanonical", emailCanonical)
                .query(Integer.class)
                .single();
    }
}
