package io.github.returnaction.fitness.exercisecatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExerciseCatalogServiceApplicationTest {

    // JPA-репозитории отключены в test-профиле, поэтому бины чтения подменяются моками.
    @MockitoBean
    private io.github.returnaction.fitness.exercisecatalog.catalog.validation.ValidateChallengeTargetUseCase
            validateChallengeTargetUseCase;

    @MockitoBean
    private io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseCategoryListService
            exerciseCategoryListService;

    @MockitoBean
    private io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseListService
            exerciseListService;

    @LocalServerPort
    private int port;

    @Test
    void healthIsAvailableAndOtherRequestsAreDenied() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> health = client.send(request("/actuator/health"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertNotNull(health.headers().firstValue("X-Request-Id").orElse(null));
            HttpResponse<String> denied = client.send(request("/unknown"), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode());
            assertNotNull(denied.headers().firstValue("X-Request-Id").orElse(null));
            HttpResponse<String> metrics = client.send(request("/actuator/prometheus"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("jvm_memory"));
            HttpResponse<String> env = client.send(request("/actuator/env"), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, env.statusCode());
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    }
}
