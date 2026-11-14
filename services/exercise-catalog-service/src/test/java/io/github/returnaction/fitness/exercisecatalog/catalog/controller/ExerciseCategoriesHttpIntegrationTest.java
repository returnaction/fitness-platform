package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseCategoriesHttpIntegrationTest {

    private static final String PATH = "/api/v1/exercise-categories";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void returnsOnlyActiveCategoriesOrderedBySortOrder() throws Exception {
        jdbc.update("""
            INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version)
            VALUES (50, 'HIDDEN', 'Скрытая', false, 5, 1),
                   (51, 'WARMUP', 'Разминка', true, 5, 1)
            """);

        var result = mvc.perform(get(PATH))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].id").value(51))
            .andExpect(jsonPath("$.items[0].code").value("WARMUP"))
            .andExpect(jsonPath("$.items[0].name").value("Разминка"))
            .andExpect(jsonPath("$.items[1].id").value(1))
            .andExpect(jsonPath("$.items[1].code").value("BODYWEIGHT"))
            .andExpect(jsonPath("$.items[2].id").value(2))
            .andExpect(jsonPath("$.items[2].code").value("CARDIO"))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("HIDDEN");
    }
}
