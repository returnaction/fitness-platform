package io.github.returnaction.fitness.challenge.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record VersionRequest(@NotNull @Positive Long version) {
}
