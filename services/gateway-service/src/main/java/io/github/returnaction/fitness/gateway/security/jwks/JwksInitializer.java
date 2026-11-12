package io.github.returnaction.fitness.gateway.security.jwks;

import com.nimbusds.jose.jwk.ECKey;
import io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.JwksStartupProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.http.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public final class JwksInitializer implements ApplicationRunner {

    private final AccessTokenProperties accessToken;
    private final JwksStartupProperties settings;
    private final HttpClient client;
    private volatile List<ECKey> keys = List.of();
    private volatile boolean stopped;
    private volatile Thread runner;
    private volatile CompletableFuture<?> active;

    public JwksInitializer(AccessTokenProperties accessToken, JwksStartupProperties settings) {
        this.accessToken = accessToken;
        this.settings = settings;
        this.client = HttpClient.newBuilder()
            .connectTimeout(settings.attemptTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public boolean ready() {
        return !stopped && !keys.isEmpty();
    }

    public List<ECKey> keys() {
        return List.copyOf(keys);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        runner = Thread.currentThread();
        long attempt = 0;
        try {
            while (!stopped && keys.isEmpty()) {
                attempt++;
                long started = System.nanoTime();
                try {
                    var request = HttpRequest.newBuilder(accessToken.jwksUri())
                        .timeout(settings.attemptTimeout())
                        .GET()
                        .build();
                    var responseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                    active = responseFuture;
                    var response = responseFuture.get(settings.attemptTimeout().toNanos(), TimeUnit.NANOSECONDS);
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("JWKS HTTP status");
                    }
                    var candidate = TrustedJwks.parse(response.body());
                    if (System.nanoTime() - started >= settings.attemptTimeout().toNanos()) {
                        throw new java.util.concurrent.TimeoutException();
                    }
                    synchronized (this) {
                        if (!stopped) {
                            keys = candidate;
                        }
                    }
                    if (ready()) {
                        log.info("JWKS startup completed: attempt={}, duration_ms={}", attempt,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception exception) {
                    log.warn("JWKS startup failed: attempt={}, failure_type={}, duration_ms={}", attempt,
                        exception.getClass().getSimpleName(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                } finally {
                    var current = active;
                    if (current != null) {
                        current.cancel(true);
                    }
                    active = null;
                }
                if (!stopped && keys.isEmpty()) {
                    TimeUnit.NANOSECONDS.sleep(settings.retryDelay().toNanos());
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            runner = null;
        }
    }

    @PreDestroy
    public synchronized void stop() {
        stopped = true;
        var current = active;
        if (current != null) {
            current.cancel(true);
        }
        var owner = runner;
        if (owner != null) {
            owner.interrupt();
        }
        client.shutdownNow();
    }
}
