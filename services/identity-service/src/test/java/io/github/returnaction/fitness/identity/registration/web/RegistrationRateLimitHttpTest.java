package io.github.returnaction.fitness.identity.registration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationOperationRepository;
import io.github.returnaction.fitness.identity.registration.service.RegistrationCompletionWaiter;
import io.github.returnaction.fitness.identity.registration.service.RegistrationRecoveryService;
import io.github.returnaction.fitness.identity.registration.service.RegistrationSessionService;
import io.github.returnaction.fitness.identity.session.repository.SessionVerificationRepository;
import io.github.returnaction.fitness.identity.session.service.LoginService;
import io.github.returnaction.fitness.identity.registration.repository.RoleRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "identity.registration.rate-limit.capacity=5"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationRateLimitHttpTest {
    // Тестируем HTTP и БД без запуска фоновых задач и реальных gRPC-соединений.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.grpc.ProfileCreationClient accountsClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.grpc.WalletCreationClient coinsClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.worker.ProfileCreationScheduler accountsScheduler;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.registration.worker.WalletCreationScheduler coinsScheduler;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.identity.session.grpc.SecureSessionGrpcServer sessionGrpcServer;

    @org.springframework.test.context.DynamicPropertySource
    static void jwtKeys(org.springframework.test.context.DynamicPropertyRegistry registry) {
        io.github.returnaction.fitness.identity.TestJwtKeys.register(registry);
    }

    @MockitoBean
    private jakarta.persistence.EntityManager entityManager;

    @MockitoBean
    private io.github.returnaction.fitness.identity.registration.repository.RecoveryAccessRepository recoveryAccessRepository;

    @MockitoBean
    private io.github.returnaction.fitness.identity.registration.repository.RegistrationReadinessRepository registrationReadinessRepository;

    @MockitoBean
    private io.github.returnaction.fitness.identity.session.repository.LoginRepository loginRepository;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private UserCredentialRepository credentials;

    @MockitoBean
    private RoleRepository roles;

    @MockitoBean
    private UserRoleRepository userRoles;

    @MockitoBean
    private RegistrationOperationRepository operations;

    @MockitoBean
    private RegistrationLeaseRepository leases;

    @MockitoBean
    private SessionVerificationRepository sessionVerificationRepository;

    @MockitoBean
    private io.github.returnaction.fitness.identity.session.repository.RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private RegistrationRecoveryService registrationRecoveryService;

    @MockitoBean
    private LoginService pendingLoginService;

    @MockitoBean
    private RegistrationSessionService sessions;

    @MockitoBean
    private RegistrationCompletionWaiter readinessWaiter;

    @MockitoBean
    private TransactionTemplate transactions;

    @Test
    void authenticationErrorsDoNotExposeInputInResponsesOrLogs() throws Exception {
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        root.addAppender(logs);
        String email = "private.user@@example.test";
        String validEmail = "person@example.test";
        String password = "secret-password-006";
        String token = "secret-refresh-006-" + "x".repeat(257);
        String validToken = "e436670d-252f-47dd-a536-6200f9ac4144." + "A".repeat(43);
        try {
            String invalidCredentials = mvc.perform(post("/api/v1/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.110");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String invalidRefresh = mvc.perform(post("/api/v1/auth/refresh")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.110");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + token + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String malformed = mvc.perform(post("/api/v1/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.110");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{\"password\":\"" + password + "\","))
                    .andExpect(status().isBadRequest())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            doThrow(new IllegalStateException(email + " " + password + " " + token)).when(pendingLoginService)
                    .login(any());
            String unexpected = mvc.perform(post("/api/v1/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.110");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{\"email\":\"" + validEmail + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isInternalServerError())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            doThrow(new org.springframework.dao.DataAccessResourceFailureException(validEmail + " " + validToken))
                    .when(refreshTokenRepository)
                    .findSessionAndUserIds(any());
            String databaseFailure = mvc.perform(post("/api/v1/auth/refresh")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.110");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + validToken + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            for (String response : java.util.List.of(invalidCredentials, invalidRefresh, malformed, unexpected,
                    databaseFailure)) {
                assertThat(response).doesNotContain(email, validEmail, password, token, validToken);
            }
            for (var event : logs.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(email, validEmail, password, token, validToken);
                if (event.getThrowableProxy() != null) {
                    assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()))
                            .doesNotContain(email, validEmail, password, token, validToken);
                }
            }
        } finally {
            root.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void logoutAllRejectsInvalidTokenWithMandatoryVerifier() throws Exception {
        mvc.perform(post("/api/v1/auth/logout-all")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.62");
                    return request;
                })
                .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_access_token"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void logoutDatabaseFailureReturnsSafe503() throws Exception {
        String secret = "private@example.com secret-token SELECT password";
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(secret))
                .when(refreshTokenRepository)
                .findSessionAndUserIds(any());
        String token = "e436670d-252f-47dd-a536-6200f9ac4144." + "A".repeat(43);
        String response = mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + token + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("identity_unavailable"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain("private@example.com", "secret-token", "SELECT", "password", token);
    }

    @Test
    void logoutRejectsInvalidHttpRequests() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
        mvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        mvc.perform(post("/api/v1/auth/logout").contentType("text/plain").content("token"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/auth/logout-all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_access_token"));
    }

    @Test
    void logoutEndpointsHaveIndependentLimits() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/v1/auth/logout")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.61");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{\"refresh_token\":\"a\"}"))
                    .andExpect(status().isNoContent());
            mvc.perform(post("/api/v1/auth/logout-all")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.61");
                        return request;
                    }))
                    .andExpect(status().isUnauthorized());
        }
        for (String path : java.util.List.of("/api/v1/auth/logout", "/api/v1/auth/logout-all")) {
            mvc.perform(post(path)
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.61");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{\"refresh_token\":\"a\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));
        }
    }

    @Test
    void databaseFailureReturnsSafeStructured503() throws Exception {
        String secret = "private@example.com secret-token SELECT password";
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(secret)).when(pendingLoginService)
                .login(any());
        String requestId = "c70001b3-869b-4b7f-ae43-245db8f653f2";
        String body = mvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.32");
                    return request;
                })
                .header("X-Request-Id", requestId)
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"password\":\"a\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.error.code").value("identity_unavailable"))
                .andExpect(jsonPath("$.error.request_id").value(requestId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("private@example.com", "secret-token", "SELECT", "password");
    }

    @Test
    void missingOrMalformedBodyReturnsStructured400() throws Exception {
        String requestId = "79f031b8-383b-44b0-8d70-615f057b0068";
        for (String path : java.util.List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh")) {
            for (String body : java.util.List.of("", "{")) {
                mvc.perform(post(path)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.30");
                            return request;
                        })
                        .header("X-Request-Id", requestId)
                        .header("Idempotency-Key", "8f7b8c45-b32d-4914-bf93-cc49d337c255")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(header().string("X-Request-Id", requestId))
                        .andExpect(jsonPath("$.error.code").value("invalid_request"))
                        .andExpect(jsonPath("$.error.request_id").value(requestId))
                        .andExpect(jsonPath("$.error.details").isEmpty());
            }
        }
    }

    @Test
    void refreshValidationReturnsStructured422() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.31");
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.details[0].field").value("refreshToken"));
    }

    @Test
    void invalidRefreshFieldsKeepTheirValidationContract() throws Exception {
        String requestId = "9c54845a-cece-4f1e-83b1-428f1e299ddf";
        for (String body : java.util.List.of(
                "{}", "{\"refresh_token\":null}", "{\"refresh_token\":\"\"}",
                "{\"refresh_token\":\"  \"}", "{\"refresh_token\":\"" + "x".repeat(257) + "\"}")) {
            mvc.perform(post("/api/v1/auth/refresh")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.90");
                        return request;
                    })
                    .header("X-Request-Id", requestId)
                    .contentType(APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(header().string("X-Request-Id", requestId))
                    .andExpect(jsonPath("$.error.code").value("validation_error"))
                    .andExpect(jsonPath("$.error.message").value("Check the submitted data."))
                    .andExpect(jsonPath("$.error.request_id").value(requestId))
                    .andExpect(jsonPath("$.error.details[0].field").value("refreshToken"))
                    .andExpect(jsonPath("$.error.details[0].code").value("invalid"));
        }
    }

    @Test
    void unsupportedRequestContentTypeReturnsStructured415() throws Exception {
        String requestId = "7b74832b-303f-42b4-943e-16ffbe415efa";
        for (String path : java.util.List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh")) {
            mvc.perform(post(path)
                    .header("X-Request-Id", requestId)
                    .header("Idempotency-Key", "8f7b8c45-b32d-4914-bf93-cc49d337c255")
                    .contentType("text/plain")
                    .content("{}"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(header().string("X-Request-Id", requestId))
                    .andExpect(jsonPath("$.error.code").value("unsupported_media_type"))
                    .andExpect(jsonPath("$.error.request_id").value(requestId))
                    .andExpect(jsonPath("$.error.details").isEmpty());
        }
    }

    @Test
    void unsupportedAcceptReturns406WithoutBody() throws Exception {
        String requestId = "7b74832b-303f-42b4-943e-16ffbe415efa";
        for (String path : java.util.List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh")) {
            mvc.perform(post(path)
                    .header("X-Request-Id", requestId)
                    .header("Idempotency-Key", "8f7b8c45-b32d-4914-bf93-cc49d337c255")
                    .contentType(APPLICATION_JSON)
                    .accept("application/xml")
                    .content("{}"))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(header().string("X-Request-Id", requestId))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        }
    }

    @Test
    void refreshHasSeparateThirtyRequestLimit() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/v1/auth/refresh")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.21");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/auth/refresh")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.21");
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("{"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void loginHasSeparateTenRequestLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.20");
                        return request;
                    })
                    .contentType(APPLICATION_JSON)
                    .content("{"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.20");
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("{"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(post("/api/v1/auth/register")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.20");
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSixthRequestAndIgnoresForgedForwardedHeaders() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/register")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.10");
                        return request;
                    })
                    .header("X-Forwarded-For", "198.51.100." + (i + 1))
                    .contentType(APPLICATION_JSON)
                    .content("{"))
                    .andExpect(status().isBadRequest());
        }
        String response = mvc.perform(post("/api/v1/auth/register")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.10");
                    return request;
                })
                .header("X-Forwarded-For", "198.51.100.99")
                .header("Idempotency-Key", "f46ac10b-58cc-4372-a567-0e02b2c3d479")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"private@example.com\",\"password\":\"private-password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response)
                .doesNotContain("private@example.com", "private-password", "f46ac10b-58cc-4372-a567-0e02b2c3d479");
        mvc.perform(post("/api/v1/auth/register")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.11");
                    return request;
                })
                .contentType(APPLICATION_JSON)
                .content("{"))
                .andExpect(status().isBadRequest());
    }
}
