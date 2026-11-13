package io.github.returnaction.fitness.accounts.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.dao.DataIntegrityViolationException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.validation-timeout=500"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class ProfileReadIntegrationTest {

    private static final String PATH = "/api/v1/accounts/me";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    HikariDataSource dataSource;
    @MockitoBean
    SecureGrpcServer grpcServer;

    private UUID createProfile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profiles (user_id, public_profile_id, nickname) VALUES (?, ?, ?)", id,
                UUID.randomUUID(), "p_" + id.toString().substring(0, 20));
        return id;
    }

    private String bearer(UUID id) {
        // Gateway has already verified the signature; Accounts reads the trusted
        // claims.
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(("{\"sub\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)) + ".AQ";
    }

    @Test
    @Order(1)
    void returnsAllEightStoredFieldsWithoutChangingTheRow() throws Exception {
        UUID id = createProfile();
        jdbc.update("""
                UPDATE user_profiles SET first_name=?, last_name=?, bio=?, date_of_birth=DATE '2000-02-29',
                    timezone='Europe/Moscow', visibility='PUBLIC', version=7 WHERE user_id=?
                """, "Анна", "О’Коннор", "Привет 👋\nВторая строка", id);
        var before = jdbc.queryForMap("SELECT * FROM user_profiles WHERE user_id=?", id);
        var result = mvc.perform(get(PATH).header("Authorization", bearer(id)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.nickname").value(before.get("nickname")))
                .andExpect(jsonPath("$.firstName").value("Анна"))
                .andExpect(jsonPath("$.lastName").value("О’Коннор"))
                .andExpect(jsonPath("$.bio").value("Привет 👋\nВторая строка"))
                .andExpect(jsonPath("$.dateOfBirth").value("2000-02-29"))
                .andExpect(jsonPath("$.timezone").value("Europe/Moscow"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.version").value(7))
                .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(8);
        assertThat(UUID.fromString(result.getResponse().getHeader("X-Request-Id"))).isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM user_profiles WHERE user_id=?", id)).isEqualTo(before);
    }

    @Test
    @Order(2)
    void keepsExplicitNullsAndCannotSelectAnotherOwner() throws Exception {
        UUID owner = createProfile();
        UUID other = createProfile();
        for (UUID id : new UUID[]{owner, other}) {
            String nickname = jdbc.queryForObject("SELECT nickname FROM user_profiles WHERE user_id=?", String.class,
                    id);
            var result = mvc
                    .perform(get(PATH).header("Authorization", bearer(id))
                            .header("X-User-Id", other.toString())
                            .content("{\"userId\":\"" + other + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nickname").value(nickname))
                    .andReturn();
            var json = mapper.readTree(result.getResponse().getContentAsString());
            assertThat(json.size()).isEqualTo(8);
            for (String field : new String[]{"firstName", "lastName", "bio", "dateOfBirth"}) {
                assertThat(json.has(field)).isTrue();
                assertThat(json.get(field).isNull()).isTrue();
            }
        }
        mvc.perform(get(PATH).header("Authorization", bearer(owner)).queryParam("userId", other.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void missingProfileReturns503WithoutCreatingAnything() throws Exception {
        Long count = jdbc.queryForObject("SELECT count(*) FROM user_profiles", Long.class);
        assertUnavailable(UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profiles", Long.class)).isEqualTo(count);
    }

    @Test
    @Order(4)
    void unicodeLengthsAgreeWithActualPostgresVarcharColumns() {
        UUID id = createProfile();
        String name = "𐐀".repeat(100);
        String bio = "😀".repeat(500);
        jdbc.update("UPDATE user_profiles SET first_name=?, last_name=?, bio=? WHERE user_id=?", name, name, bio, id);
        assertThat(jdbc.queryForObject("SELECT char_length(first_name) FROM user_profiles WHERE user_id=?",
                Integer.class, id)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT bio FROM user_profiles WHERE user_id=?", String.class, id))
                .isEqualTo(bio);
        assertThatThrownBy(() -> jdbc.update("UPDATE user_profiles SET first_name=? WHERE user_id=?", name + "a", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE user_profiles SET last_name=? WHERE user_id=?", name + "a", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE user_profiles SET bio=? WHERE user_id=?", bio + "a", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(99)
    void stoppedDatabaseReturns503() throws Exception {
        UUID id = createProfile();
        POSTGRES.stop();
        dataSource.getHikariPoolMXBean().softEvictConnections();
        assertUnavailable(id);
    }

    private void assertUnavailable(UUID id) throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(get(PATH).header("Authorization", bearer(id)).header("X-Request-Id", requestId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.error.code").value("profile_unavailable"))
                .andExpect(jsonPath("$.error.requestId").value(requestId))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception",
                        "SELECT", "jdbc:", bearer(id)));
    }
}
