package io.github.returnaction.fitness.coins.dailyreward.repository;

import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyRewardRequestRepository extends JpaRepository<DailyRewardRequestEntity, UUID> {

    Optional<DailyRewardRequestEntity> findByUserIdAndKeyDigest(UUID userId, byte[] keyDigest);
}
