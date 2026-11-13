package io.github.returnaction.fitness.accounts.profile.dto;

import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.accounts.AccountsServiceApplication;
import io.github.returnaction.fitness.accounts.profile.service.ProfileService;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = AccountsServiceApplication.class, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "profile-contract-test"})
@Import(ProfileHttpContractTest.ContractEndpoints.class)
class ProfileHttpContractTest {

    private static final String PATH = "/api/v1/accounts/me";
    private static final String ID = "2c160d4b-0f61-4702-99e1-99713d0c9326";
    private static final List<String> FIELDS = List.of("nickname", "firstName", "lastName", "bio", "dateOfBirth",
            "timezone", "visibility", "version");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper mapper;
    @MockitoBean
    private ProfileCreationService creationService;
    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private SecureGrpcServer grpcServer;

    private static Map<String, String> fields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("nickname", "\"Player_1\"");
        fields.put("firstName", "\"Anna\"");
        fields.put("lastName", "null");
        fields.put("bio", "null");
        fields.put("dateOfBirth", "\"2000-02-29\"");
        fields.put("timezone", "\"UTC\"");
        fields.put("visibility", "\"PRIVATE\"");
        fields.put("version", "1");
        return fields;
    }

    private static String json(Map<String, String> fields) {
        return fields.entrySet()
                .stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String with(String field, String value) {
        var fields = fields();
        fields.put(field, value);
        return json(fields);
    }

    static Stream<Arguments> invalidBodies() {
        var cases = new ArrayList<Arguments>();
        for (String field : FIELDS) {
            var missing = fields();
            missing.remove(field);
            cases.add(Arguments.of("missing " + field, json(missing)));
        }
        for (String field : List.of("nickname", "firstName", "lastName", "bio", "timezone")) {
            for (String value : List.of("123", "1.5", "true", "false", "[]", "{}")) {
                cases.add(Arguments.of(field + "=" + value, with(field, value)));
            }
        }
        for (String value : List.of("1.5", "1.0", "\"1\"", "\"\"", "\"  \"", "true", "[]", "{}",
                "9223372036854775808")) {
            cases.add(Arguments.of("version=" + value, with("version", value)));
        }
        for (String value : List.of("0", "1.0", "\"0\"", "\"UNKNOWN\"", "\"private\"", "\"\"", "\"  \"", "true", "[]",
                "{}")) {
            cases.add(Arguments.of("visibility=" + value, with("visibility", value)));
        }
        for (String value : List.of("0", "1.0", "true", "[]", "[2000,1,2]", "{}", "\"\"", "\"  \"", "\"2000-02-30\"",
                "\"2001-02-29\"", "\"2000-1-2\"", "\"2000-01-02T00:00:00Z\"", "\" 2000-01-02 \"")) {
            cases.add(Arguments.of("dateOfBirth=" + value, with("dateOfBirth", value)));
        }
        cases.add(Arguments.of("unknown field", with("userId", "\"private-input\"")));
        var snake = fields();
        snake.put("first_name", snake.remove("firstName"));
        cases.add(Arguments.of("snake_case", json(snake)));
        for (String value : List.of("", "{", "null", "[]", "true", "123", json(fields()) + " {}")) {
            cases.add(Arguments.of("body=" + value, value));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidProfileJsonReturnsCommon400(String description, String body) throws Exception {
        mvc.perform(put(PATH).header("X-Request-Id", ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Request-Id", ID))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.requestId").value(ID))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("private-input", "Exception", "stackTrace"));
    }

    @Test
    void nullableFieldsRemainExplicitAndResponseContainsExactlyContractFields() throws Exception {
        var values = fields();
        for (String field : List.of("firstName", "lastName", "bio", "dateOfBirth")) {
            values.put(field, "null");
        }
        var result = mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(json(values)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        var node = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.propertyNames()).containsExactlyInAnyOrderElementsOf(FIELDS);
        for (String field : List.of("firstName", "lastName", "bio", "dateOfBirth")) {
            assertThat(node.get(field).isNull()).as(field).isTrue();
        }
        assertThat(node.get("visibility").asString()).isEqualTo("PRIVATE");
    }

    @Test
    void requiredNullValuesAreLeftForBusinessValidation() {
        var values = fields();
        for (String field : List.of("nickname", "timezone", "visibility", "version")) {
            values.put(field, "null");
        }
        var request = mapper.readValue(json(values), UpdateProfileRequest.class);
        assertThat(request.nickname()).isNull();
        assertThat(request.timezone()).isNull();
        assertThat(request.visibility()).isNull();
        assertThat(request.version()).isNull();
    }

    @Test
    void emptyTextAndOutOfRangeValuesAreLeftForBusinessValidation() {
        var values = fields();
        values.put("firstName", "\"   \" ");
        values.put("dateOfBirth", "\"1800-01-01\"");
        values.put("version", "0");
        var request = mapper.readValue(json(values), UpdateProfileRequest.class);
        assertThat(request.firstName()).isEqualTo("   ");
        assertThat(request.dateOfBirth()).isEqualTo(LocalDate.of(1800, 1, 1));
        assertThat(request.version()).isZero();
    }

    @Test
    void bothEnumValuesAndIsoDateRoundTrip() throws Exception {
        for (String visibility : List.of("PUBLIC", "PRIVATE")) {
            mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
                    .content(with("visibility", "\"" + visibility + "\"")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.visibility").value(visibility))
                    .andExpect(jsonPath("$.dateOfBirth").value("2000-02-29"));
        }
    }

    @Test
    void queryStringsAreRejectedForBothMethods() throws Exception {
        for (String query : List.of("foo=bar", "version=1", "")) {
            mvc.perform(get(PATH).with(request -> {
                request.setQueryString(query);
                return request;
            })).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_request"));
            mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(json(fields())).with(request -> {
                request.setQueryString(query);
                return request;
            })).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_request"));
        }
        when(profileService.getOwnProfile(null)).thenReturn(
                new ProfileResponse("Player_1", null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L));
        mvc.perform(get(PATH)).andExpect(status().isOk());
        mvc.perform(get("/actuator/health?foo=bar")).andExpect(status().isOk());
    }

    @Test
    void unsupportedAndMissingContentTypesReturn415() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.TEXT_PLAIN).content(json(fields())))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));
        mvc.perform(put(PATH).content(json(fields())))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));
    }

    @TestConfiguration
    static class ContractEndpoints {

        @Bean
        @Order(0)
        SecurityFilterChain contractChain(HttpSecurity http) throws Exception {
            return http.securityMatcher(PATH)
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }

        @Bean
        ContractController contractController() {
            return new ContractController();
        }
    }

    // Test-only HTTP adapter: no profile persistence or production authentication
    // is implemented here.
    @RestController
    @Profile("profile-contract-test")
    static class ContractController {

        @PutMapping(value = PATH, consumes = "application/json", produces = "application/json")
        ProfileResponse putProfile(@RequestBody UpdateProfileRequest request) {
            return new ProfileResponse(request.nickname(), request.firstName(), request.lastName(), request.bio(),
                    request.dateOfBirth(), request.timezone(), request.visibility(), request.version());
        }

    }
}
