package io.github.returnaction.fitness.accounts.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.github.returnaction.fitness.accounts.AccountsServiceApplication;
import io.github.returnaction.fitness.accounts.profile.service.ProfileService;
import io.github.returnaction.fitness.accounts.profile.service.ProfileUpdateService;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import java.util.List;
import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.verify;
import java.util.UUID;
import io.github.returnaction.fitness.accounts.profile.dto.ProfileResponse;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = AccountsServiceApplication.class, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "profile-security-test"})
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
    private ProfileService profileService;
    @MockitoBean
    private ProfileUpdateService profileUpdateService;

    @MockitoBean
    private SecureGrpcServer grpcServer;

    private String bearer() {
        return "Bearer " + ProfileAuthenticationFilterTest.token("{\"sub\":\"" + ID + "\"}");
    }

    @Test
    void productionChainAdmitsBothOperationsWithNoRolesAndNoSession() throws Exception {
        when(profileService.getOwnProfile(UUID.fromString(ID)))
                .thenReturn(new ProfileResponse(ID, null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L));
        var input = new UpdateProfileRequest("Player_1", null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L);
        when(profileService.updateOwnProfile(UUID.fromString(ID), input))
            .thenReturn(new ProfileResponse(ID, null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 2L));
        String body = """
            {"nickname":"Player_1","firstName":null,"lastName":null,"bio":null,
             "dateOfBirth":null,"timezone":"UTC","visibility":"PRIVATE","version":1}
            """;
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT)) {
            var result = mvc.perform(request(method, PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nickname").value(ID))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        verify(profileService).updateOwnProfile(UUID.fromString(ID), input);
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
        when(profileService.getOwnProfile(UUID.fromString(ID))).thenThrow(new IllegalStateException("test failure"));
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

}
