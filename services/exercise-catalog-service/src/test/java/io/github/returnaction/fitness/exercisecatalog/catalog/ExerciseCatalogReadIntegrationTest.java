package io.github.returnaction.fitness.exercisecatalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCatalogResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCatalogResponse.Category;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCatalogResponse.Exercise;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCatalogResponse.Variation;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlGroup;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@SqlGroup({
    @Sql(statements = "TRUNCATE catalog_categories, catalog_exercises, catalog_variations"),
    @Sql("/db/changelog/changes/004-seed-initial-exercise-catalog.sql")
})
class ExerciseCatalogReadIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void readsSeededCatalogWithThreeStatements() throws Exception {
        var categories = readCatalog().categories();
        assertThat(categories).extracting(Category::code).containsExactly("BODYWEIGHT", "CARDIO");
        var exercises = categories.stream().flatMap(category -> category.exercises().stream()).toList();
        assertThat(exercises).hasSize(6);
        assertThat(exercises).flatExtracting(Exercise::variations).hasSize(12);
        for (Exercise exercise : exercises) {
            assertThat(exercise.variations()).filteredOn(v -> v.code().equals("STANDARD"))
                .singleElement().extracting(Variation::id).isEqualTo(exercise.defaultVariationId());
        }
    }

    @Test
    void returnsEmptyArrayWhenCatalogIsEmpty() throws Exception {
        jdbc.execute("TRUNCATE catalog_categories, catalog_exercises, catalog_variations");
        assertThat(readCatalog().categories()).isEmpty();
    }

    @Test
    void filtersInactiveChainsAndSortsEveryLevelByOrderThenId() throws Exception {
        jdbc.update("UPDATE catalog_categories SET is_active = false WHERE code = 'CARDIO'");
        jdbc.update("""
            INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version)
            VALUES (4, 'EMPTY_B', 'Empty B', true, 0, 1), (3, 'EMPTY_A', 'Empty A', true, 0, 1)
            """);
        jdbc.update("UPDATE catalog_exercises SET is_active = false WHERE code = 'SQUAT'");
        jdbc.update("UPDATE catalog_variations SET is_active = false WHERE id IN (3, 7)");
        jdbc.update("UPDATE catalog_exercises SET sort_order = 0 WHERE code IN ('PLANK', 'JUMPING_JACK')");
        jdbc.update("UPDATE catalog_variations SET sort_order = 0 WHERE id IN (4, 5)");

        var categories = readCatalog().categories();
        assertThat(categories).extracting(Category::code).containsExactly("EMPTY_A", "EMPTY_B", "BODYWEIGHT");
        assertThat(categories.get(0).exercises()).isEmpty();
        assertThat(categories.get(1).exercises()).isEmpty();
        var exercises = categories.get(2).exercises();
        assertThat(exercises).extracting(Exercise::code).containsExactly("JUMPING_JACK", "PLANK", "PUSH_UP");
        var pushUp = exercises.get(2);
        assertThat(pushUp.variations()).extracting(Variation::code).containsExactly("NARROW", "WIDE", "STANDARD", "KNEE");
        assertThat(pushUp.defaultVariationId()).isEqualTo(pushUp.variations().get(2).id());
    }

    private ExerciseCatalogResponse readCatalog() throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(new byte[32]));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/exercise-catalog"))
            .header("Authorization", "Bearer " + token.serialize()).GET().build();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        try (var client = HttpClient.newHttpClient()) {
            long before = statistics.getPrepareStatementCount();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(statistics.getPrepareStatementCount() - before).isEqualTo(3);
            assertThat(mapper.readTree(response.body()).findValues("unit")).isEmpty();
            return mapper.readValue(response.body(), ExerciseCatalogResponse.class);
        }
    }
}
