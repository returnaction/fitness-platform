package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(10)
class HttpsDeadlineTest {

    private static final ServerSocket LISTENER = listener();
    @MockitoBean
    private JwksInitializer keys;
    @MockitoBean
    private SessionVerificationClient sessions;
    @LocalServerPort
    private int port;
    @Autowired
    private ScheduledThreadPoolExecutor scheduler;

    private static ServerSocket listener() {
        try {
            return new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("gateway.request.timeout", () -> "500ms");
        properties.add("gateway.upstreams.services.IDENTITY.base-url",
                () -> "https://localhost:" + LISTENER.getLocalPort());
    }

    @AfterAll
    static void close() throws IOException {
        LISTENER.close();
    }

    @Test
    void cancelsStalledTlsHandshake() throws Exception {
        when(keys.ready()).thenReturn(true);
        try (var client = HttpClient.newHttpClient()) {
            long started = System.nanoTime();
            var pending = client
                    .sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                            .timeout(Duration.ofSeconds(3))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.ofString());
            LISTENER.setSoTimeout(3000);
            try (var accepted = LISTENER.accept()) {
                accepted.setSoTimeout(2000);
                assertThat(accepted.getInputStream().read()).isNotEqualTo(-1);
                var response = pending.get();
                assertThat(response.statusCode()).isEqualTo(504);
                assertThat(response.body()).contains("upstream_timeout");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
                assertThat(scheduler.getQueue()).isEmpty();
            }
        }
    }
}
