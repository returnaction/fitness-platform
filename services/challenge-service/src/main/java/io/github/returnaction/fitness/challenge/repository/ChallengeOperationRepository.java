package io.github.returnaction.fitness.challenge.repository;

import io.github.returnaction.fitness.challenge.domain.ChallengeOperationStatus;
import io.github.returnaction.fitness.challenge.entity.ChallengeOperationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChallengeOperationRepository extends JpaRepository<ChallengeOperationEntity, UUID> {

    /**
     * Незавершённая операция пользователя. Предикат совпадает с частичным
     * уникальным индексом ux_challenge_operations_active.
     */
    @Query(value = """
            SELECT * FROM challenge_operations
            WHERE challenge_id = :challengeId
              AND user_id = :userId
              AND status IN ('PENDING', 'RESERVED', 'RELEASING')
            ORDER BY created_at, id
            LIMIT 1
            """, nativeQuery = true)
    Optional<ChallengeOperationEntity> findFirstActive(@Param("challengeId") UUID challengeId,
                                                       @Param("userId") UUID userId);

    @Query(value = """
            SELECT * FROM challenge_operations
            WHERE challenge_id = :challengeId
              AND user_id = :userId
              AND kind = 'PUBLISH'
              AND status = 'CONFIRMED'
              AND hold_id IS NOT NULL
            ORDER BY created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ChallengeOperationEntity> findConfirmedPublishWithHold(
            @Param("challengeId") UUID challengeId, @Param("userId") UUID userId);

    default List<ChallengeOperationEntity> findByChallengeIdAndStatus(UUID challengeId,
                                                                      ChallengeOperationStatus status) {
        return findByChallengeIdAndStatus(challengeId, status.name());
    }

    @Query(value = """
            SELECT * FROM challenge_operations
            WHERE challenge_id = :challengeId AND status = :status
            ORDER BY created_at, id
            """, nativeQuery = true)
    List<ChallengeOperationEntity> findByChallengeIdAndStatus(@Param("challengeId") UUID challengeId,
                                                              @Param("status") String status);
}
