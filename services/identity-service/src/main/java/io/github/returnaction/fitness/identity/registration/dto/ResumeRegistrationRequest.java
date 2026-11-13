package io.github.returnaction.fitness.identity.registration.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResumeRegistrationRequest(@NotNull @Min(1) Long expectedVersion,
                                        @NotBlank @Size(max = 500) String reason) {
}
