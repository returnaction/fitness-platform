package io.github.returnaction.fitness.accounts.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.github.returnaction.fitness.accounts.AccountsServiceApplication;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = AccountsServiceApplication.class, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "profile-security-test"})
@Import(ProfileAuthenticationSecurityTest.Endpoints.class)
class ProfileAuthenticationSecurityTest {

    private static final String PATH = "/api/v1/accounts/me";
    private static final String ID = ProfileAuthenticationFilterTest.ID;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private FilterChainProxy securityChain;
    @MockitoBean
    private ProfileCreationService creationService;
    @MockitoBean
    private SecureGrpcServer grpcServer;

    private String bearer() {
        return "Bearer " + ProfileAuthenticationFilterTest.token("{\"sub\":\"" + ID + "\"}");
    }

    @Test
    void productionChainAdmitsBothOperationsWithNoRolesAndNoSession() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT)) {
            var result = mvc.perform(request(method, PATH).header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(ID))
                    .andExpect(jsonPath("$.roles").isEmpty())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void malformedTokenHasCommon401AndSameRequestId() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer secret-invalid-value").header("X-Request-Id", ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-Id", ID))
                .andExpect(jsonPath("$.error.code").value("invalid_access_token"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.requestId").value(ID))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-invalid-value", "Exception"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void controllerFailureRemains500AndFollowingRequestIsUnauthenticated() throws Exception {
        mvc.perform(get(PATH).header("Authorization", bearer()).header("X-Test-Fail", "true"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void otherMethodsAndPathsRemainDeniedAndManagementSkipsBearerParsing() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD,
                HttpMethod.OPTIONS)) {
            mvc.perform(request(method, PATH).header("Authorization", bearer())).andExpect(status().isUnauthorized());
        }
        for (String path : List.of(PATH + "/", PATH + "/other", "/not-public")) {
            mvc.perform(get(path).header("Authorization", bearer())).andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/actuator/health", "/actuator/info", "/actuator/prometheus")) {
            mvc.perform(get(path).header("Authorization", "Bearer broken")).andExpect(status().isOk());
        }
    }

    @Test
    void filterIsRegisteredOnlyInsideSecurityChain() {
        assertThat(context.getBeansOfType(ProfileAuthenticationFilter.class)).isEmpty();
        assertThat(
                securityChain.getFilters(PATH).stream().filter(ProfileAuthenticationFilter.class::isInstance).count())
                .isEqualTo(1);
    }

    @TestConfiguration
    static class Endpoints {

        @Bean
        ContextController contextController() {
            return new ContextController();
        }
    }

    @RestController
    @Profile("profile-security-test")
    static class ContextController {

        @RequestMapping(value = PATH, method = {RequestMethod.GET, RequestMethod.PUT})
        Map<String, Object> currentUser(@RequestHeader(value = "X-Test-Fail", defaultValue = "false") boolean fail) {
            if (fail)
                throw new IllegalStateException("test controller failure");
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            return Map.of("userId", authentication.getPrincipal(), "roles", authentication.getAuthorities());
        }
    }
}
