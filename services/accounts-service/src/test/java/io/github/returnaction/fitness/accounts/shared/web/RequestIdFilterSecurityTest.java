package io.github.returnaction.fitness.accounts.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.accounts.AccountsServiceApplication;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = AccountsServiceApplication.class, webEnvironment = WebEnvironment.MOCK, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestIdFilterSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileCreationService profileCreationService;

    @MockitoBean
    private SecureGrpcServer secureGrpcServer;

    @Test
    void addsRequestIdToRequestRejectedBySecurity() throws Exception {
        mockMvc.perform(get("/not-public"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Request-Id"));
    }
}
