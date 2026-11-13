package io.github.returnaction.fitness.accounts.profile.repository;

import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProfileRepository extends JpaRepository<UserProfileEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO user_profiles (user_id, public_profile_id, nickname)
            VALUES (:userId, :publicProfileId, :nickname)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("publicProfileId") UUID publicProfileId,
        @Param("nickname") String nickname);
}
