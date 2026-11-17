package io.github.returnaction.fitness.challenge.repository;

import io.github.returnaction.fitness.challenge.entity.ChallengeParticipantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChallengeParticipantRepository extends JpaRepository<ChallengeParticipantEntity, UUID> {

    @Query(value = "SELECT count(*) FROM challenge_participants WHERE challenge_id = :challengeId",
            nativeQuery = true)
    long countByChallengeId(@Param("challengeId") UUID challengeId);

    @Query(value = """
            SELECT * FROM challenge_participants
            WHERE challenge_id = :challengeId
            ORDER BY join_sequence
            """, nativeQuery = true)
    List<ChallengeParticipantEntity> findByChallengeIdOrderByJoinSequenceAsc(
            @Param("challengeId") UUID challengeId);

    @Query(value = """
            SELECT * FROM challenge_participants
            WHERE challenge_id = :challengeId AND user_id = :userId
            """, nativeQuery = true)
    Optional<ChallengeParticipantEntity> findByChallengeIdAndUserId(@Param("challengeId") UUID challengeId,
                                                                    @Param("userId") UUID userId);
}
