package io.github.returnaction.fitness.identity.registration.controller;

import io.github.returnaction.fitness.identity.registration.dto.RegistrationRecoveryResponse;
import io.github.returnaction.fitness.identity.registration.dto.ResumeRequest;
import io.github.returnaction.fitness.identity.registration.dto.ResumeResponse;
import io.github.returnaction.fitness.identity.registration.service.RegistrationRecoveryService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity/registration-operations")
@RequiredArgsConstructor
public class RegistrationRecoveryController {
    private final RegistrationRecoveryService recovery;

    @GetMapping("/{id}")
    public RegistrationRecoveryResponse read(@PathVariable UUID id) {
        var state = recovery.read(id);
        return new RegistrationRecoveryResponse(
                state.operationId(), state.status(), state.version(), state.recoveryBlockedAt(), state.lastErrorCode());
    }

    @PostMapping("/{id}/resume")
    public ResumeResponse resume(@PathVariable UUID id, @AuthenticationPrincipal UUID actor,
                                 @Valid @RequestBody ResumeRequest request) {
        var state = recovery.resume(id, actor, request.expectedVersion(), request.reason());
        return new ResumeResponse(state.operationId(), state.status(), state.version());
    }
}
