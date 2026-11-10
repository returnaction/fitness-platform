package io.github.returnaction.fitness.gateway.security;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import io.github.returnaction.fitness.gateway.configuration.GatewayProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Bounded key cache. Never caches session authorization. */
@Component
public final class JwksKeys implements AutoCloseable {
    private final GatewayProperties settings;
    private final Clock clock;
    private final HttpClient client;
    private JWKSet keys = new JWKSet();
    private Instant expires = Instant.MIN;
    private Instant nextRefresh = Instant.MIN;
    private boolean lastRefreshFailed;

    @org.springframework.beans.factory.annotation.Autowired
    public JwksKeys(GatewayProperties settings) {
        this(settings, Clock.systemUTC());
    }

    JwksKeys(GatewayProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.httpTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public synchronized ECKey get(String keyId) {
        Instant now = clock.instant();
        var key = keys.getKeyByKeyId(keyId);
        if (now.isBefore(expires) && key instanceof ECKey ec)
            return ec;
        if (!now.isBefore(nextRefresh))
            refresh(now);
        if (!now.isBefore(expires) || lastRefreshFailed)
            throw GatewayFailure.unavailable();
        key = keys.getKeyByKeyId(keyId);
        if (!(key instanceof ECKey ec))
            throw GatewayFailure.invalid();
        return ec;
    }

    private void refresh(Instant now) {
        nextRefresh = now.plusSeconds(settings.jwksRefreshSeconds());
        try {
            var request = HttpRequest.newBuilder(settings.jwksUrl())
                    .timeout(Duration.ofMillis(settings.httpTimeoutMillis()))
                    .GET()
                    .build();
            // The body handler completes within the request timeout; only a configured
            // trusted endpoint is used.
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 65536) {
                throw GatewayFailure.unavailable();
            }
            var fetched = JWKSet.parse(response.body());
            if (fetched.getKeys().isEmpty() || fetched.getKeys().size() > 32
                    || fetched.getKeys().stream().anyMatch(k -> k.isPrivate())) {
                throw GatewayFailure.unavailable();
            }
            keys = fetched;
            expires = now.plusSeconds(settings.jwksTtlSeconds());
            lastRefreshFailed = false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            lastRefreshFailed = true;
            throw GatewayFailure.unavailable();
        } catch (Exception exception) {
            lastRefreshFailed = true;
            throw GatewayFailure.unavailable();
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
