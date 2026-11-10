package io.github.returnaction.fitness.identity.security.controller;

import io.github.returnaction.fitness.identity.security.jwt.PublicJwtKeys;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class JwksController {
    private final PublicJwtKeys keys;

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> keys() {
        return keys.jwks();
    }
}
