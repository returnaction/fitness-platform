package io.github.returnaction.fitness.coins.dailyreward.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.coins.dailyreward.config.DailyRewardProperties;
import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardStatusResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.shared.persistence.ConnectionFailures;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repositories and clock are shared")
public class DailyRewardQueryService {

    private final WalletRepository wallets;
    private final DailyRewardClaimRepository claims;
    private final DailyRewardProperties properties;
    private final Clock clock;

    public DailyRewardStatusResponse getStatus(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        try {
            var wallet = wallets.findById(userId).orElseThrow(WalletInvariantViolationException::new);
            var latestClaim = claims.findFirstByUserIdOrderByClaimedAtDesc(userId);
            Instant now = clock.instant();
            Instant nextClaimAt = latestClaim
                .map(claim -> DailyRewardSchedule.nextClaimAt(claim.getClaimDate(), claim.getClaimedAt()))
                .orElse(null);
            boolean available = nextClaimAt == null || DailyRewardSchedule.isAvailable(now, nextClaimAt);

            return new DailyRewardStatusResponse(
                available,
                properties.amount(),
                latestClaim.map(DailyRewardClaimEntity::getClaimDate).orElse(null),
                available ? null : nextClaimAt,
                now,
                wallet.getAvailableBalance(),
                wallet.getLockedBalance()
            );
        } catch (DataAccessResourceFailureException
                 | TransientDataAccessResourceException
                 | QueryTimeoutException
                 | CannotCreateTransactionException exception) {
            throw new CoinsUnavailableException(exception);
        } catch (JpaSystemException exception) {
            if (ConnectionFailures.causedByConnectionFailure(exception)) {
                throw new CoinsUnavailableException(exception);
            }
            throw exception;
        }
    }
}
