package io.github.returnaction.fitness.challenge.repository;

import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChallengeRepository extends JpaRepository<ChallengeEntity, UUID> {

    /**
     * Читает challenge под пессимистической блокировкой строки. Все переходы
     * состояния, подтверждение участия и резервирование join_sequence
     * выполняются в одной транзакции после этого чтения.
     */
    @Query(value = "SELECT * FROM challenges WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<ChallengeEntity> findByIdForUpdate(@Param("id") UUID id);
}
