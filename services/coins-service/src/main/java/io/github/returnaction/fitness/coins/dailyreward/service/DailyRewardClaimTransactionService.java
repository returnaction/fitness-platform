package io.github.returnaction.fitness.coins.dailyreward.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.coins.dailyreward.config.DailyRewardProperties;
import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardOutcome;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.shared.persistence.ConnectionFailures;
import io.github.returnaction.fitness.coins.shared.persistence.LockFailures;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryEntity;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryType;
import io.github.returnaction.fitness.coins.wallet.entity.WalletEntity;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import io.github.returnaction.fitness.coins.wallet.repository.LedgerEntryRepository;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционная часть ежедневной награды.
 *
 * <p>Кошелёк блокируется первым: это сериализует claim одного пользователя.
 * Claim, ledger, баланс и снимок идемпотентности фиксируются одной транзакцией.
 * Ожидание блокировки ограничено {@code lock_timeout}, чтобы не удерживать
 * соединение пула слишком долго.</p>
 */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
    justification = "Spring-managed repositories, entity manager and clock are shared")
public class DailyRewardClaimTransactionService {

    private static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '2s'";

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledgerEntries;
    private final DailyRewardClaimRepository claims;
    private final DailyRewardRequestRepository requests;
    private final DailyRewardProperties properties;
    private final Clock clock;
    private final EntityManager entityManager;

    @Transactional(timeout = 5)
    public DailyRewardClaimResponse claim(UUID userId, byte[] keyDigest) {

        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(keyDigest, "keyDigest");

        try {
            entityManager.createNativeQuery(LOCK_TIMEOUT).executeUpdate();

            var wallet = wallets.findByIdForUpdate(userId)
                .orElseThrow(WalletInvariantViolationException::new);

            var existingRequest = requests.findByUserIdAndKeyDigest(userId, keyDigest);
            if (existingRequest.isPresent()) {
                return existingRequest.get().getResponseSnapshot();
            }

            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var latestClaim = claims.findFirstByUserIdOrderByClaimedAtDesc(userId);
            Instant nextClaimAt = latestClaim
                .map(claim -> DailyRewardSchedule.nextClaimAt(claim.getClaimDate(), claim.getClaimedAt()))
                .orElse(null);

            if (nextClaimAt != null && !DailyRewardSchedule.isAvailable(now, nextClaimAt)) {
                DailyRewardClaimEntity lastClaim = latestClaim.orElseThrow();
                var response = new DailyRewardClaimResponse(
                    0,
                    wallet.getAvailableBalance(),
                    wallet.getLockedBalance(),
                    lastClaim.getClaimDate(),
                    true,
                    nextClaimAt,
                    now
                );
                saveRequest(userId, keyDigest, DailyRewardOutcome.ALREADY_CLAIMED, lastClaim.getId(), response, now);
                return response;
            }

            return grant(userId, keyDigest, wallet, now);
        } catch (PessimisticLockingFailureException exception) {
            throw new CoinsUnavailableException(exception);
        } catch (DataAccessResourceFailureException
                 | TransientDataAccessResourceException
                 | QueryTimeoutException exception) {
            throw new CoinsUnavailableException(exception);
        } catch (JpaSystemException exception) {
            if (ConnectionFailures.causedByConnectionFailure(exception)
                    || LockFailures.causedByLockConflict(exception)) {
                throw new CoinsUnavailableException(exception);
            }
            throw exception;
        }
    }

    private DailyRewardClaimResponse grant(UUID userId,
                                           byte[] keyDigest,
                                           WalletEntity wallet,
                                           Instant now) {
        long amount = properties.amount();
        UUID claimId = UUID.randomUUID();
        UUID ledgerEntryId = UUID.randomUUID();
        LocalDate claimDate = LocalDate.ofInstant(now, ZoneOffset.UTC);

        wallet.creditAvailable(amount, now);
        ledgerEntries.save(new LedgerEntryEntity(
            ledgerEntryId,
            userId,
            claimId,
            0,
            LedgerEntryType.DAILY_REWARD,
            amount,
            0,
            null,
            null,
            now
        ));
        claims.save(new DailyRewardClaimEntity(claimId, userId, claimDate, now, amount, ledgerEntryId));

        Instant nextClaimAt = DailyRewardSchedule.nextClaimAt(claimDate, now);
        var response = new DailyRewardClaimResponse(
            amount,
            wallet.getAvailableBalance(),
            wallet.getLockedBalance(),
            claimDate,
            false,
            nextClaimAt,
            now
        );
        saveRequest(userId, keyDigest, DailyRewardOutcome.GRANTED, claimId, response, now);
        return response;
    }

    private void saveRequest(UUID userId,
                             byte[] keyDigest,
                             DailyRewardOutcome outcome,
                             UUID claimId,
                             DailyRewardClaimResponse response,
                             Instant now) {
        requests.save(new DailyRewardRequestEntity(
            UUID.randomUUID(),
            userId,
            keyDigest,
            outcome,
            claimId,
            response,
            now
        ));
    }
}
