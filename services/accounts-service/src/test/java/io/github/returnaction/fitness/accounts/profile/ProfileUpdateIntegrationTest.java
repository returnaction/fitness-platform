package io.github.returnaction.fitness.accounts.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileUnavailableException;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileVersionConflictException;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import io.github.returnaction.fitness.accounts.profile.service.ProfileService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.returnaction.fitness.accounts.profile.exception.NicknameAlreadyTakenException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Locale;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.datasource.hikari.minimum-idle=0",
    "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.validation-timeout=500"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
@ActiveProfiles("test")
class ProfileUpdateIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String PATH = "/api/v1/accounts/me";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HikariDataSource dataSource;
    @Autowired ProfileService service;
    @Autowired ProfileRepository repository;
    @MockitoBean SecureGrpcServer grpcServer;

    private UserProfileEntity createProfile() {
        UUID id = UUID.randomUUID();
        return repository.saveAndFlush(new UserProfileEntity(id, UUID.randomUUID(), "p_" + id.toString().substring(0, 20)));
    }

    private UpdateProfileRequest request(String nickname, long version) {
        return new UpdateProfileRequest(nickname, "  Анна  ", "О’Коннор", "bio😀", LocalDate.of(2000, 2, 29),
            "Europe/Moscow", ProfileVisibility.PUBLIC, version);
    }

    @Test
    void returnsAllUpdatedFieldsAndCommitsBeforeReturning() {
        var entity = createProfile();
        var result = service.updateOwnProfile(entity.getUserId(), request(entity.getNickname(), 1));
        assertThat(result.nickname()).isEqualTo(entity.getNickname());
        assertThat(result.firstName()).isEqualTo("Анна");
        assertThat(result.lastName()).isEqualTo("О’Коннор");
        assertThat(result.bio()).isEqualTo("bio😀");
        assertThat(result.dateOfBirth()).isEqualTo(LocalDate.of(2000, 2, 29));
        assertThat(result.timezone()).isEqualTo("Europe/Moscow");
        assertThat(result.visibility()).isEqualTo(ProfileVisibility.PUBLIC);
        assertThat(result.version()).isEqualTo(2L);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(service.getOwnProfile(entity.getUserId())).isEqualTo(result);
        var stored = repository.findById(entity.getUserId()).orElseThrow();
        assertThat(stored.getUpdatedAt()).isAfter(entity.getUpdatedAt());
        assertThat(stored.getPublicProfileId()).isEqualTo(entity.getPublicProfileId());
    }

    @Test
    void clearsNullableFieldsAndIncrementsVersionEvenWithoutChanges() {
        var entity = createProfile();
        service.updateOwnProfile(entity.getUserId(), request(entity.getNickname(), 1));
        var result = service.updateOwnProfile(entity.getUserId(), new UpdateProfileRequest(entity.getNickname(),
            "  ", null, " ", null, "UTC", ProfileVisibility.PRIVATE, 2L));
        assertThat(result.firstName()).isNull();
        assertThat(result.lastName()).isNull();
        assertThat(result.bio()).isNull();
        assertThat(result.dateOfBirth()).isNull();
        assertThat(result.visibility()).isEqualTo(ProfileVisibility.PRIVATE);
        assertThat(result.version()).isEqualTo(3L);
        var unchanged = service.updateOwnProfile(entity.getUserId(), new UpdateProfileRequest(entity.getNickname(),
            null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 3L));
        assertThat(unchanged.version()).isEqualTo(4L);
        assertThat(service.getOwnProfile(entity.getUserId())).isEqualTo(unchanged);
    }

    @Test
    void distinguishesMissingProfileFromStaleVersionWithoutWriting() {
        var entity = createProfile();
        var before = service.getOwnProfile(entity.getUserId());
        assertThatThrownBy(() -> service.updateOwnProfile(entity.getUserId(), request(entity.getNickname(), 2)))
            .isInstanceOf(ProfileVersionConflictException.class);
        assertThat(service.getOwnProfile(entity.getUserId())).isEqualTo(before);
        long count = repository.count();
        assertThatThrownBy(() -> service.updateOwnProfile(UUID.randomUUID(), request("MissingUser", 1)))
            .isInstanceOf(ProfileUnavailableException.class);
        assertThat(repository.count()).isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nicknameConflictRollsBackAllFieldsAndVersion(boolean changeCase) {
        var owner = createProfile();
        var other = createProfile();
        var before = service.getOwnProfile(owner.getUserId());
        String occupiedNickname = changeCase ? other.getNickname().toUpperCase(Locale.ROOT) : other.getNickname();
        var updatedAt = repository.findById(owner.getUserId()).orElseThrow().getUpdatedAt();
        assertThatThrownBy(() -> service.updateOwnProfile(owner.getUserId(), request(occupiedNickname, 1)))
            .isInstanceOf(NicknameAlreadyTakenException.class);
        assertThat(repository.findById(owner.getUserId()).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(service.getOwnProfile(owner.getUserId())).isEqualTo(before);
        assertThat(service.updateOwnProfile(owner.getUserId(), request(owner.getNickname(), 1)).version()).isEqualTo(2L);
    }

    private String bearer(UUID id) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(("{\"sub\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)) + ".AQ";
    }

    private void assertPutError(UUID id, String body, int expectedStatus, String code) throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(put(PATH).header("Authorization", bearer(id)).header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Request-Id", requestId))
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.requestId").value(requestId))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Exception", "SELECT", "user_profiles_nickname_unique", "private-invalid-input"));
    }

    @Test
    void httpPutCommitsNormalizedProfileAndGetReturnsIt() throws Exception {
        var owner = createProfile();
        var other = createProfile();
        var otherBefore = service.getOwnProfile(other.getUserId());
        String requestId = UUID.randomUUID().toString();
        var result = mvc.perform(put(PATH).header("Authorization", bearer(owner.getUserId()))
                .header("X-Request-Id", requestId).header("X-User-Id", other.getUserId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(owner.getNickname(), 1))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Request-Id", requestId))
            .andExpect(jsonPath("$.firstName").value("Анна"))
            .andExpect(jsonPath("$.dateOfBirth").value("2000-02-29"))
            .andExpect(jsonPath("$.version").value(2))
            .andReturn();
        var saved = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(saved.propertyNames()).containsExactlyInAnyOrder("nickname", "firstName", "lastName", "bio",
            "dateOfBirth", "timezone", "visibility", "version");
        var read = mvc.perform(get(PATH).header("Authorization", bearer(owner.getUserId())))
            .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(read.getResponse().getContentAsString())).isEqualTo(saved);
        assertThat(service.getOwnProfile(other.getUserId())).isEqualTo(otherBefore);

        var clear = new UpdateProfileRequest(owner.getNickname(), "  ", null, "  ", null, "UTC", ProfileVisibility.PRIVATE, 2L);
        var cleared = mvc.perform(put(PATH).header("Authorization", bearer(owner.getUserId()))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(clear)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3)).andReturn();
        var json = mapper.readTree(cleared.getResponse().getContentAsString());
        for (String field : new String[]{"firstName", "lastName", "bio", "dateOfBirth"}) {
            assertThat(json.has(field)).isTrue();
            assertThat(json.get(field).isNull()).isTrue();
        }
        assertThat(service.getOwnProfile(owner.getUserId()).version()).isEqualTo(3L);
    }

    @Test
    void httpConflictsAndMissingProfileHaveCommonErrorsAndDoNotWrite() throws Exception {
        var owner = createProfile();
        var other = createProfile();
        var before = service.getOwnProfile(owner.getUserId());
        assertPutError(owner.getUserId(), mapper.writeValueAsString(request(other.getNickname().toUpperCase(Locale.ROOT), 1)),
            409, "nickname_already_taken");
        assertPutError(owner.getUserId(), mapper.writeValueAsString(request(owner.getNickname(), 2)),
            409, "profile_version_conflict");
        assertThat(service.getOwnProfile(owner.getUserId())).isEqualTo(before);
        UUID absent = UUID.randomUUID();
        assertPutError(absent, mapper.writeValueAsString(request("MissingProfile", 1)), 503, "profile_unavailable");
        assertThat(repository.existsById(absent)).isFalse();
    }

    @Test
    void httpRejectsMalformedInvalidAndUnauthenticatedRequestsWithoutWriting() throws Exception {
        var owner = createProfile();
        var before = service.getOwnProfile(owner.getUserId());
        assertPutError(owner.getUserId(), "{private-invalid-input", 400, "invalid_request");
        var invalid = new UpdateProfileRequest("!", "private-invalid-input1", null, null, null, "Mars/Base", null, 0L);
        assertPutError(owner.getUserId(), mapper.writeValueAsString(invalid), 422, "validation_error");
        String valid = mapper.writeValueAsString(request(owner.getNickname(), 1));
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(put(PATH).header("Authorization", bearer(owner.getUserId()))
                .contentType(MediaType.TEXT_PLAIN).content(valid))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(put(PATH).header("Authorization", bearer(owner.getUserId()))
                .queryParam("userId", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isBadRequest());
        assertThat(service.getOwnProfile(owner.getUserId())).isEqualTo(before);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void httpPutReturns503WhenDisposableDatabaseIsStopped() throws Exception {
        var owner = createProfile();
        String body = mapper.writeValueAsString(request(owner.getNickname(), 1));
        POSTGRES.stop();
        dataSource.getHikariPoolMXBean().softEvictConnections();
        assertPutError(owner.getUserId(), body, 503, "profile_unavailable");
    }
}
