package io.github.returnaction.fitness.accounts.profile.repository.projection;

import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;

import java.time.LocalDate;

public interface UpdateProfileProjection {

    String getNickname();
    String getFirstName();
    String getLastName();
    String getBio();
    LocalDate getDateOfBirth();
    String getTimezone();
    ProfileVisibility getVisibility();
    Long getVersion();
}
