package io.github.returnaction.fitness.accounts.profile.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;

import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfileResponse(

    String nickname,
    String firstName,
    String lastName,
    String bio,
    LocalDate dateOfBirth,
    String timezone,
    ProfileVisibility visibility,
    Long version) {
}
