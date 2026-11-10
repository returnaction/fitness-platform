package io.github.returnaction.fitness.identity.session.controller;

import io.github.returnaction.fitness.identity.session.dto.LoginRequest;
import io.github.returnaction.fitness.identity.session.dto.LoginTokenResponse;
import io.github.returnaction.fitness.identity.session.service.LoginService;
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
public class LoginController {

    private final LoginService loginService;

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LoginTokenResponse> login(@Valid @RequestBody LoginRequest request) {

        LoginTokenResponse response = loginService.login(request);

        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }
}
