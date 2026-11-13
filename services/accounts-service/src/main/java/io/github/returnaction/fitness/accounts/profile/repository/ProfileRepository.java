package io.github.returnaction.fitness.accounts.profile.repository;

import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import io.github.returnaction.fitness.accounts.profile.repository.projection.UpdateProfileProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<UserProfileEntity, UUID> {

    @Modifying
    @Query(value = """
        INSERT INTO user_profiles (user_id, public_profile_id, nickname)
        VALUES (:userId, :publicProfileId, :nickname)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId,
                       @Param("publicProfileId") UUID publicProfileId,
                       @Param("nickname") String nickname);

    @Query(value = """
        UPDATE user_profiles
        SET nickname      = :nickname,
            first_name    = :firstName,
            last_name     = :lastName,
            bio           = :bio,
            date_of_birth = :dateOfBirth,
            timezone      = :timezone,
            visibility    = :visibility,
            version       = version + 1,
            updated_at    = CURRENT_TIMESTAMP
        WHERE user_id = :userId
          AND version = :expectedVersion
        RETURNING nickname,
                  first_name AS "firstName",
                  last_name AS "lastName",
                  bio,
                  date_of_birth AS "dateOfBirth",
                  timezone,
                  visibility,
                  version
        """, nativeQuery = true)
    Optional<UpdateProfileProjection> updateProfile(@Param("userId") UUID userId,
                                                    @Param("expectedVersion") long expectedVersion,
                                                    @Param("nickname") String nickname,
                                                    @Param("firstName") String firstName,
                                                    @Param("lastName") String lastName,
                                                    @Param("bio") String bio,
                                                    @Param("dateOfBirth") LocalDate dateOfBirth,
                                                    @Param("timezone") String timezone,
                                                    @Param("visibility") String visibility
    );
}
