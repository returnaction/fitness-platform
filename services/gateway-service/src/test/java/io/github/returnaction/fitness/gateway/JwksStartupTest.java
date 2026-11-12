package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import io.github.returnaction.fitness.gateway.security.jwks.TrustedJwks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

@Timeout(40)
class JwksStartupTest {

    @Test
    void slowBodyTimesOutAndShutdownCancelsRetry() throws Exception {
        var firstRequest = new java.util.concurrent.CountDownLatch(1);
        var secondRequest = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/keys", exchange -> {
                calls.incrementAndGet();
                firstRequest.countDown();
                secondRequest.countDown();
                try {
                    exchange.sendResponseHeaders(200, 1000);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var loader = new JwksInitializer(
                    new io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/keys"), "issuer",
                            "audience"),
                    new io.github.returnaction.fitness.gateway.configuration.properties.JwksStartupProperties(
                            Duration.ofMillis(200), Duration.ofMillis(100)));
            var run = workers.submit(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()));
            try {
                assertThat(firstRequest.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(secondRequest.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(loader.ready()).isFalse();
                assertThat(loader.keys()).isEmpty();
                loader.stop();
                run.get(3, TimeUnit.SECONDS);
                int stoppedCalls = calls.get();
                Thread.sleep(400);
                assertThat(calls.get()).isEqualTo(stoppedCalls);
            } finally {
                loader.stop();
                release.countDown();
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void shutdownInterruptsLongRetryPause() throws Exception {
        var failed = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            failed.countDown();
        });
        server.start();
        var loader = new JwksInitializer(
                new io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/keys"), "issuer",
                        "audience"),
                new io.github.returnaction.fitness.gateway.configuration.properties.JwksStartupProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(30)));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = worker.submit(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()));
            try {
                assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue();
                loader.stop();
                run.get(3, TimeUnit.SECONDS);
                assertThat(loader.ready()).isFalse();
            } finally {
                loader.stop();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidCandidatesAndRetainsOnlyPublicKeys() throws Exception {
        var key = new ECKeyGenerator(Curve.P_256).keyID("trusted").generate();
        String valid = new JWKSet(key.toPublicJWK()).toString();
        assertThat(TrustedJwks.parse(valid)).hasSize(1);
        assertThatThrownBy(() -> TrustedJwks.parse("{}")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> TrustedJwks.parse("{\"keys\":[]}")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> TrustedJwks.parse(new JWKSet(key).toString(false))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> TrustedJwks.parse(new JWKSet(java.util.List.of(key.toPublicJWK(),
                key.toPublicJWK())).toString())).isInstanceOf(Exception.class);
        var wrongCurve = new ECKeyGenerator(Curve.P_384).keyID("wrong").generate();
        assertThatThrownBy(() -> TrustedJwks.parse(new JWKSet(wrongCurve.toPublicJWK()).toString()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void directApiIsBlockedUntilKeysLoadWhileManagementStaysAvailable() throws Exception {
        var payload = new AtomicReference<>("{\"keys\":[]}");
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            calls.incrementAndGet();
            byte[] body = payload.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var contextRef = new AtomicReference<ConfigurableApplicationContext>();
        try (var executor = Executors.newSingleThreadExecutor(); var client = HttpClient.newHttpClient()) {
            var application = new SpringApplication(GatewayApplication.class);
            application.addListeners((org.springframework.context.ApplicationListener<ContextRefreshedEvent>) event -> {
                if (event.getApplicationContext().getParent() == null) {
                    contextRef.set((ConfigurableApplicationContext) event.getApplicationContext());
                }
            });
            var startup = executor.submit(() -> application.run("--spring.profiles.active=test",
                    "--gateway.security.access-token.jwks-uri=http://127.0.0.1:" + server.getAddress().getPort()
                            + "/keys",
                    "--gateway.security.jwks-startup.retry-delay=100ms",
                    "--gateway.security.jwks-startup.attempt-timeout=1s"));
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (contextRef.get() == null && System.nanoTime() < until) {
                    Thread.sleep(20);
                }
                var context = contextRef.get();
                assertThat(context).isNotNull();
                int api = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                int management = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
                var blocked = get(client, api, "/api/v1/auth/login");
                assertThat(blocked.statusCode()).isEqualTo(503);
                assertThat(blocked.body()).contains("identity_unavailable", "request_id");
                assertThat(get(client, api, "/actuator/health/liveness").statusCode()).isEqualTo(503);
                assertThat(get(client, management, "/actuator/health/liveness").statusCode()).isEqualTo(200);
                assertThat(get(client, management, "/actuator/health/readiness").statusCode()).isEqualTo(503);
                assertThat(startup.isDone()).isFalse();
                payload.set(new JWKSet(new ECKeyGenerator(Curve.P_256).keyID("trusted").generate().toPublicJWK())
                        .toString());
                startup.get(10, TimeUnit.SECONDS);
                assertThat(context.getBean(JwksInitializer.class).keys()).hasSize(1);
                assertThat(get(client, management, "/actuator/health/readiness").statusCode()).isEqualTo(200);
                assertThat(get(client, api, "/api/v1/auth/login").statusCode()).isEqualTo(405);
                int completedCalls = calls.get();
                payload.set("invalid");
                Thread.sleep(350);
                assertThat(calls.get()).isEqualTo(completedCalls);
                assertThat(get(client, management, "/actuator/health/readiness").statusCode()).isEqualTo(200);
            } finally {
                var context = contextRef.get();
                if (context != null) {
                    context.close();
                }
                startup.cancel(true);
            }
        } finally {
            server.stop(0);
        }
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
