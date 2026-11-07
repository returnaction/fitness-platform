package io.github.returnaction.fitness.identity.registration.controller;

import io.github.returnaction.fitness.identity.registration.application.PendingLoginService;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.shared.web.ApiErrorResponse;
import io.github.returnaction.fitness.identity.shared.web.RequestIdFilter;
import io.github.returnaction.fitness.identity.shared.web.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PendingLoginController {
    private final PendingLoginService login;

    public PendingLoginController(PendingLoginService login) {
        this.login = login;
    }

    @PostMapping("/api/v1/auth/login")
    ResponseEntity<ApiErrorResponse> login(
            @RequestBody RegisterUserRequest request, HttpServletRequest servletRequest) {
        var outcome = login.login(request);
        String code = switch (outcome) {
            case INVALID -> "invalid_credentials";
            case PENDING -> "registration_pending";
            case ACTIVE_LOGIN_UNAVAILABLE -> "identity_unavailable";
        };
        String message = switch (outcome) {
            case INVALID -> "Invalid email or password.";
            case PENDING -> "Registration is being prepared. Try again later.";
            case ACTIVE_LOGIN_UNAVAILABLE -> "Login is temporarily unavailable.";
        };
        Object attribute = servletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = attribute instanceof String value
                ? value
                : RequestIdResolver.resolve(null).toString();
        var body = new ApiErrorResponse(new ApiErrorResponse.Error(code, message, List.of(), requestId));
        return ResponseEntity.status(
                        outcome == PendingLoginService.Outcome.INVALID
                                ? HttpStatus.UNAUTHORIZED
                                : HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
