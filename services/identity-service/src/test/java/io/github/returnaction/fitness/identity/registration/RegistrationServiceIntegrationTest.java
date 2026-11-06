package io.github.returnaction.fitness.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.identity.registration.application.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationService;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.entity.UserCredentialEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserStatus;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
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
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
