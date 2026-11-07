package io.github.returnaction.fitness.identity.registration.controller;

import io.github.returnaction.fitness.identity.registration.application.PublicJwtKeys;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "identity.jwt.enabled", havingValue = "true")
public class JwksController {
    private final PublicJwtKeys keys;

    public JwksController(PublicJwtKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> keys() {
        return keys.jwks();
    }
}
