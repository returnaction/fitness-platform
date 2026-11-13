package io.github.returnaction.fitness.accounts.profile.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;

import java.time.LocalDate;

public record UpdateProfileRequest(

                                   @JsonProperty(value = "nickname", required = true) String nickname,

                                   @JsonProperty(value = "firstName", required = true) String firstName,

                                   @JsonProperty(value = "lastName", required = true) String lastName,

                                   @JsonProperty(value = "bio", required = true) String bio,

                                   @JsonDeserialize(using = ProfileDateDeserializer.class) @JsonProperty(value = "dateOfBirth", required = true) LocalDate dateOfBirth,

                                   @JsonProperty(value = "timezone", required = true) String timezone,

                                   @JsonProperty(value = "visibility", required = true) ProfileVisibility visibility,

                                   @JsonProperty(value = "version", required = true) Long version

) {
}
