package io.github.returnaction.fitness.identity.registration.web;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.identity.registration.application.PendingLoginService;
import io.github.returnaction.fitness.identity.registration.application.RegistrationSessionService;
import io.github.returnaction.fitness.identity.registration.recovery.RegistrationRecoveryService;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationOperationRepository;
import io.github.returnaction.fitness.identity.registration.repository.RoleRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository;
import io.github.returnaction.fitness.identity.registration.worker.RegistrationLeaseRepository;
import io.github.returnaction.fitness.identity.session.SessionVerificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
            "identity.registration.rate-limit.capacity=5"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationRateLimitHttpTest {
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
    private RegistrationRecoveryService registrationRecoveryService;

    @MockitoBean
    private PendingLoginService pendingLoginService;

    @MockitoBean
    private RegistrationSessionService sessions;

    @MockitoBean
    private TransactionTemplate transactions;

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
        mvc.perform(post("/api/v1/auth/register")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.99")
                        .contentType(APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"));
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
