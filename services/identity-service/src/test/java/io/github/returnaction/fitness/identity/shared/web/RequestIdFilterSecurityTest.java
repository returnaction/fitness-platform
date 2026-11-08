package io.github.returnaction.fitness.identity.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationOperationRepository;
import io.github.returnaction.fitness.identity.registration.service.RegistrationReadinessWaiter;
import io.github.returnaction.fitness.identity.registration.service.RegistrationRecoveryService;
import io.github.returnaction.fitness.identity.registration.service.RegistrationSessionService;
import io.github.returnaction.fitness.identity.session.repository.SessionVerificationRepository;
import io.github.returnaction.fitness.identity.session.service.PendingLoginService;
import io.github.returnaction.fitness.identity.registration.repository.RoleRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestIdFilterSecurityTest {

    @MockitoBean
    private jakarta.persistence.EntityManager entityManager;

    @MockitoBean
    private io.github.returnaction.fitness.identity.registration.repository.RecoveryAccessRepository recoveryAccessRepository;

    @MockitoBean
    private io.github.returnaction.fitness.identity.registration.repository.RegistrationReadinessRepository registrationReadinessRepository;

    @MockitoBean
    private io.github.returnaction.fitness.identity.session.repository.PendingLoginRepository pendingLoginRepository;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserCredentialRepository userCredentialRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private UserRoleRepository userRoleRepository;

    @MockitoBean
    private RegistrationOperationRepository operationRepository;

    @MockitoBean
    private RegistrationLeaseRepository registrationLeaseRepository;

    @MockitoBean
    private SessionVerificationRepository sessionVerificationRepository;

    @MockitoBean
    private RegistrationRecoveryService registrationRecoveryService;

    @MockitoBean
    private PendingLoginService pendingLoginService;

    @MockitoBean
    private RegistrationSessionService registrationSessionService;

    @MockitoBean
    private RegistrationReadinessWaiter readinessWaiter;

    // В этом тесте DataSource намеренно отключён: проверяется только порядок
    // web/security-фильтров.
    @MockitoBean
    private TransactionTemplate transactionTemplate;

    @Test
    void addsRequestIdToRequestRejectedBySecurity() throws Exception {
        mockMvc.perform(get("/not-public"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void wrongMethodOnRegistrationIsRejectedBySecurity() throws Exception {
        mockMvc.perform(get("/api/v1/auth/register"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Request-Id"));
    }
}
