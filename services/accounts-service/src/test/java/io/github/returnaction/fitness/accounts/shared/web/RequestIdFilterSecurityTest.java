package io.github.returnaction.fitness.accounts.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.accounts.AccountsServiceApplication;
import io.github.returnaction.fitness.accounts.profile.service.ProfileService;
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
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestIdFilterSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private ProfileCreationService profileCreationService;

    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private SecureGrpcServer secureGrpcServer;

    @Test
    void anonymousRequestReturns401WithCommonJsonAndGatewayRequestId() throws Exception {
        String id = "2c160d4b-0f61-4702-99e1-99713d0c9326";
        mockMvc.perform(get("/not-public").header("X-Request-Id", id))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-Id", id))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("invalid_access_token"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.requestId").value(id));
    }

    @Test
    void authenticatedRequestToDeniedPathReturns403WithSameGeneratedId() throws Exception {
        var result = mockMvc.perform(get("/not-public").with(request -> {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken("test-user", null, List.of()));
            new RequestAttributeSecurityContextRepository().saveContext(context, request,
                    new MockHttpServletResponse());
            return request;
        }))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("access_denied"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).isNotBlank();
        jsonPath("$.error.requestId").value(id).match(result);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void managementRemainsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test
    void applicationJacksonSettingsRejectInvalidProfileInputAndAllowExplicitNull() {
        String valid = """
                {"nickname":"Player_1","firstName":null,"lastName":null,"bio":null,
                 "dateOfBirth":null,"timezone":"UTC","visibility":"PRIVATE","version":1}
                """;
        assertThat(jsonMapper.readValue(valid, UpdateProfileRequest.class).firstName()).isNull();
        for (String invalid : List.of(valid.replace("\"version\":1", "\"version\":1.5"),
                valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("\"PRIVATE\"", "0"),
                valid.replace("\"PRIVATE\"", "\"UNKNOWN\""), valid.replace("\"PRIVATE\"", "\"private\""),
                valid.replace("\"version\":1", "\"version\":1,\"unexpected\":true"))) {
            assertThatThrownBy(() -> jsonMapper.readValue(invalid, UpdateProfileRequest.class))
                    .isInstanceOf(JacksonException.class);
        }
    }
}
