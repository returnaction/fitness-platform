package io.github.returnaction.fitness.gateway.security;

import io.github.returnaction.fitness.gateway.exception.GatewayException;

import io.github.returnaction.fitness.gateway.configuration.GatewayProperties;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fixed windows with bounded storage; a full table rejects new clients instead
 * of evicting limits.
 */
@Component
public final class RequestLimits {

    private final GatewayProperties settings;
    private final Clock clock;
    private final Map<String, Window> clients = new HashMap<>();

    @Autowired
    public RequestLimits(GatewayProperties settings) {
        this(settings, Clock.systemUTC());
    }

    RequestLimits(GatewayProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public synchronized void check(String address) {
        long now = clock.millis();
        Window window = clients.get(address);
        if (window == null || now >= window.expires) {
            if (clients.size() >= settings.maxClients())
                clients.values().removeIf(value -> now >= value.expires);
            if (!clients.containsKey(address) && clients.size() >= settings.maxClients()) {
                throw new GatewayException(429, "rate_limited");
            }
            window = new Window(now + settings.ratePeriodSeconds() * 1000L);
            clients.put(address, window);
        }
        if (window.count >= settings.rateCapacity())
            throw new GatewayException(429, "rate_limited");
        window.count++;
    }

    private static final class Window {
        private final long expires;
        private int count;
        private Window(long expires) {
            this.expires = expires;
        }
    }
}
