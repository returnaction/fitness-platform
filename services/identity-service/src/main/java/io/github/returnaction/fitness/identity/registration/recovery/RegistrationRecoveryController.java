package io.github.returnaction.fitness.identity.registration.recovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity/registration-operations")
public class RegistrationRecoveryController {
    private final RegistrationRecoveryService recovery;

    public RegistrationRecoveryController(RegistrationRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping("/{id}")
    public RegistrationRecoveryService.State read(@PathVariable UUID id) {
        return recovery.read(id);
    }

    @PostMapping("/{id}/resume")
    public ResumeResponse resume(
            @PathVariable UUID id, @AuthenticationPrincipal UUID actor, @Valid @RequestBody ResumeRequest request) {
        var state = recovery.resume(id, actor, request.expectedVersion(), request.reason());
        return new ResumeResponse(state.operationId(), state.status(), state.version());
    }

    public record ResumeRequest(
            @NotNull @Min(1) Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record ResumeResponse(UUID operationId, String status, long version) {}
}
