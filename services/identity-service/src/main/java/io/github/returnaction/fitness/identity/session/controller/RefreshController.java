package io.github.returnaction.fitness.identity.session.controller;

import io.github.returnaction.fitness.identity.session.dto.RefreshRequest;
import io.github.returnaction.fitness.identity.session.dto.RefreshResponse;
import io.github.returnaction.fitness.identity.session.service.RefreshSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class RefreshController {

    private final RefreshSessionService refreshService;

    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {

        RefreshResponse response = refreshService.refresh(request.refreshToken());

        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }
}
