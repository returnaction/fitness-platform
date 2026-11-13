package io.github.returnaction.fitness.accounts.profile.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProfileJsonNamingTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void responseUsesCamelCaseAndIncludesNullableFields() {
        var response = new ProfileResponse("Player_1", "Anna", null, null,
                LocalDate.of(2000, 1, 2), "UTC", ProfileVisibility.PRIVATE, 1L);
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.size()).isEqualTo(8);
        assertThat(json.get("firstName").asString()).isEqualTo("Anna");
        assertThat(json.get("lastName").isNull()).isTrue();
        assertThat(json.get("bio").isNull()).isTrue();
        assertThat(json.get("dateOfBirth").asString()).isEqualTo("2000-01-02");
        assertThat(json.has("first_name")).isFalse();
        assertThat(json.has("last_name")).isFalse();
        assertThat(json.has("date_of_birth")).isFalse();
    }

    @Test
    void requestReadsCamelCaseFields() {
        var request = mapper.readValue("""
                {"nickname":"Player_1","firstName":"Anna","lastName":null,"bio":null,
                 "dateOfBirth":"2000-01-02","timezone":"UTC","visibility":"PRIVATE","version":1}
                """, UpdateProfileRequest.class);
        assertThat(request.firstName()).isEqualTo("Anna");
        assertThat(request.lastName()).isNull();
        assertThat(request.dateOfBirth()).isEqualTo(LocalDate.of(2000, 1, 2));
    }
}
