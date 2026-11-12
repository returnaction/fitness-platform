package io.github.returnaction.fitness.identity.session.controller;

import java.util.UUID;
import io.github.returnaction.fitness.identity.session.dto.LogoutRequest;
import io.github.returnaction.fitness.identity.session.service.LogoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class LogoutController {

    private final LogoutService service;

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {

        service.logout(request.refreshToken());

        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/logout-all", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UUID userId) {

        service.logoutAll(userId);

        return ResponseEntity.noContent().build();
    }
}
