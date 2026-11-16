package io.github.returnaction.fitness.coins.dailyreward.repository;

import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyRewardClaimRepository extends JpaRepository<DailyRewardClaimEntity, UUID> {

    Optional<DailyRewardClaimEntity> findFirstByUserIdOrderByClaimedAtDesc(UUID userId);
}
