package io.github.returnaction.fitness.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.identity.registration.application.AccessTokenSigner;
import io.github.returnaction.fitness.identity.registration.application.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationNotReadyException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationService;
import io.github.returnaction.fitness.identity.registration.application.RegistrationSessionService;
import io.github.returnaction.fitness.identity.registration.application.RegistrationTokenService;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.entity.UserCredentialEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserStatus;
import io.github.returnaction.fitness.identity.registration.grpc.AccountsProvisioningClient;
import io.github.returnaction.fitness.identity.registration.grpc.CoinsProvisioningClient;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import io.github.returnaction.fitness.identity.registration.worker.AccountsProvisioningProcessor;
import io.github.returnaction.fitness.identity.registration.worker.AccountsProvisioningScheduler;
import io.github.returnaction.fitness.identity.registration.worker.CoinsProvisioningProcessor;
import io.github.returnaction.fitness.identity.registration.worker.RegistrationLeaseRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private RegistrationSessionService sessionService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RegistrationLeaseRepository leases;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
        var scheduler = new AccountsProvisioningScheduler(leases, processor, 4, 30);
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
        throw new AssertionError("Worker did not mark the profile ready");
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
        var first = leases.claim(UUID.randomUUID(), 100, 30).stream()
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
        var second = leases.claim(UUID.randomUUID(), 100, 30).stream()
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
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30).stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.markProfileReady(profileLease)).isTrue();
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30).stream()
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
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30).stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        assertThat(leases.markProfileReady(profileLease)).isTrue();
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30).stream()
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
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30).stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.markProfileReady(profileLease);
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30).stream()
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
        var profileLease = leases.claim(UUID.randomUUID(), 100, 30).stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.markProfileReady(profileLease);
        var walletLease = leases.claimWallet(UUID.randomUUID(), 100, 30).stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        leases.markWalletReady(walletLease);
        var previous = sessionService.create(operation.operationId(), "a");
        var signer = org.mockito.Mockito.mock(AccessTokenSigner.class);
        org.mockito.Mockito.when(signer.sign(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("signing failed"));
        var service = new RegistrationTokenService(sessionService, signer);
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
    void retryAndPermanentFailureRespectLeaseAndScheduling() {
        var operation = registrationService.start(
                UUID.randomUUID(), new RegisterUserRequest("retry-" + UUID.randomUUID() + "@example.com", "a"));
        var first = leases.claim(UUID.randomUUID(), 100, 30).stream()
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
        var second = leases.claim(UUID.randomUUID(), 100, 30).stream()
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
        var operation =
                registrationService.start(UUID.randomUUID(), new RegisterUserRequest("lease-test@example.com", "a"));
        var first = leases.claim(UUID.randomUUID(), 20, 30).stream()
                .filter(lease -> lease.operationId().equals(operation.operationId()))
                .findFirst()
                .orElseThrow();
        jdbcClient
                .sql(
                        "UPDATE registration_operations SET lease_until = clock_timestamp() - interval '1 second' WHERE id = :id")
                .param("id", operation.operationId())
                .update();
        var second = leases.claim(UUID.randomUUID(), 20, 30).stream()
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
        RegistrationService.RegistrationStartResult result =
                registrationService.start(idempotencyKey, new RegisterUserRequest(" Anna@Example.com ", "a"));

        UserEntity user = userRepository.findById(result.userId()).orElseThrow();
        UserCredentialEntity credentials =
                userCredentialRepository.findById(result.userId()).orElseThrow();

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
    void rejectsEmailWithMultipleAtSigns() throws Exception {
        for (String email : List.of("a@@b", "a@b@c")) {
            mockMvc.perform(post("/api/v1/auth/register")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"a\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("validation_error"))
                    .andExpect(jsonPath("$.error.details[0].field").value("email"));
        }
    }

    @Test
    void handlesConcurrentRequestsWithSameKeyWithoutDuplicate() throws Exception {
        UUID key = UUID.randomUUID();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<RegistrationService.RegistrationStartResult> first = CompletableFuture.supplyAsync(
                    () -> registrationService.start(key, new RegisterUserRequest("same-key@example.com", "a")),
                    executor);
            CompletableFuture<RegistrationService.RegistrationStartResult> second = CompletableFuture.supplyAsync(
                    () -> registrationService.start(key, new RegisterUserRequest("same-key@example.com", "a")),
                    executor);

            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(userCount("same-key@example.com")).isEqualTo(1);
    }

    @Test
    void handlesConcurrentRequestsWithDifferentKeysAndSameEmail() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<RegistrationService.RegistrationStartResult> first = CompletableFuture.supplyAsync(
                    () -> registrationService.start(
                            UUID.randomUUID(), new RegisterUserRequest("same-email@example.com", "a")),
                    executor);
            CompletableFuture<RegistrationService.RegistrationStartResult> second = CompletableFuture.supplyAsync(
                    () -> registrationService.start(
                            UUID.randomUUID(), new RegisterUserRequest("same-email@example.com", "a")),
                    executor);

            List<CompletableFuture<RegistrationService.RegistrationStartResult>> results = List.of(first, second);
            int successfulRequests = 0;
            int conflictingRequests = 0;
            for (CompletableFuture<RegistrationService.RegistrationStartResult> result : results) {
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
