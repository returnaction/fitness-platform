package io.github.returnaction.fitness.accounts.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "public_profile_id", nullable = false, unique = true, updatable = false)
    private UUID publicProfileId;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(length = 500)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProfileVisibility visibility;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserProfileEntity(UUID userId, UUID publicProfileId, String nickname) {
        Instant now = Instant.now();
        this.userId = userId;
        this.publicProfileId = publicProfileId;
        this.nickname = nickname;
        timezone = "UTC";
        visibility = ProfileVisibility.PRIVATE;
        version = 1;
        createdAt = now;
        updatedAt = now;
    }

}
