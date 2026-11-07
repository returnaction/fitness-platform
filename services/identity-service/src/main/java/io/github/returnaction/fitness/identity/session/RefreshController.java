package io.github.returnaction.fitness.identity.session;

import io.github.returnaction.fitness.identity.shared.web.ApiErrorResponse;
import io.github.returnaction.fitness.identity.shared.web.RequestIdFilter;
import io.github.returnaction.fitness.identity.shared.web.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefreshController {
    private final ObjectProvider<RefreshTokenService> services;

    public RefreshController(ObjectProvider<RefreshTokenService> services) {
        this.services = services;
    }

    @PostMapping("/api/v1/auth/refresh")
    ResponseEntity<?> refresh(@Valid @RequestBody Request request, HttpServletRequest servletRequest) {
        var service = services.getIfAvailable();
        if (service == null) return error(servletRequest, HttpStatus.SERVICE_UNAVAILABLE, "identity_unavailable");
        var result = service.refresh(request.refreshToken());
        if (result.code() != null) {
            return error(
                    servletRequest,
                    "account_unavailable".equals(result.code()) ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                    result.code());
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new Tokens(
                        result.access(),
                        result.refresh(),
                        "Bearer",
                        (int) Math.min(900, result.remaining()),
                        result.remaining()));
    }

    private ResponseEntity<ApiErrorResponse> error(HttpServletRequest request, HttpStatus status, String code) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = value instanceof String text
                ? text
                : RequestIdResolver.resolve(null).toString();
        var body = new ApiErrorResponse(
                new ApiErrorResponse.Error(code, "Request could not be completed.", List.of(), requestId));
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }

    public record Request(@NotBlank @Size(max = 256) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=<redacted>]";
        }
    }

    private record Tokens(
            String accessToken, String refreshToken, String tokenType, int expiresIn, long refreshExpiresIn) {}
}
