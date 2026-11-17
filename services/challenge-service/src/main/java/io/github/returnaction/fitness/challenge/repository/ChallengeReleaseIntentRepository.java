package io.github.returnaction.fitness.challenge.repository;

import io.github.returnaction.fitness.challenge.domain.ReleaseIntentStatus;
import io.github.returnaction.fitness.challenge.entity.ChallengeReleaseIntentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChallengeReleaseIntentRepository extends JpaRepository<ChallengeReleaseIntentEntity, UUID> {

    @Query(value = "SELECT * FROM challenge_release_intents WHERE hold_id = :holdId", nativeQuery = true)
    Optional<ChallengeReleaseIntentEntity> findByHoldId(@Param("holdId") UUID holdId);

    default List<ChallengeReleaseIntentEntity> findByChallengeIdAndStatus(UUID challengeId,
                                                                          ReleaseIntentStatus status) {
        return findByChallengeIdAndStatus(challengeId, status.name());
    }

    @Query(value = """
            SELECT * FROM challenge_release_intents
            WHERE challenge_id = :challengeId AND status = :status
            ORDER BY created_at, id
            """, nativeQuery = true)
    List<ChallengeReleaseIntentEntity> findByChallengeIdAndStatus(@Param("challengeId") UUID challengeId,
                                                                  @Param("status") String status);
}
