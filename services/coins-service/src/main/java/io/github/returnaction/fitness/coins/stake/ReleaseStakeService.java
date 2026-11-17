package io.github.returnaction.fitness.coins.stake;

import io.github.returnaction.fitness.coins.stake.StakeRepository.OperationSnapshot;
import io.github.returnaction.fitness.coins.stake.exception.HoldChallengeMismatchException;
import io.github.returnaction.fitness.coins.stake.exception.HoldNotReleasableException;
import io.github.returnaction.fitness.coins.stake.exception.HoldNotFoundException;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.WalletNotFoundException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import java.security.MessageDigest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Освобождает активный hold ровно один раз и возвращает средства в available.
 */
@Service
@RequiredArgsConstructor
public class ReleaseStakeService {

    private static final String RELEASE_KIND = "RELEASE";
    private static final String ACTIVE = "ACTIVE";

    private final StakeRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReleaseStakeResult release(ReleaseStakeCommand command) {
        String state = repository.lockStakeSet(command.challengeId());

        byte[] requestHash = requestHash(command);
        var existing = repository.findOperation(RELEASE_KIND, command.releaseOperationId());
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        if ("SETTLED".equals(state) || "CANCELLED".equals(state)) {
            throw new HoldNotReleasableException();
        }

        var hold = repository.lockHold(command.holdId()).orElseThrow(HoldNotFoundException::new);
        if (!hold.challengeId().equals(command.challengeId())) {
            throw new HoldChallengeMismatchException();
        }
        if (!ACTIVE.equals(hold.status())) {
            throw new HoldNotReleasableException();
        }

        var wallet = repository.lockWallet(hold.userId()).orElseThrow(WalletNotFoundException::new);
        if (wallet.lockedBalance() < hold.amount()) {
            throw new WalletInvariantViolationException();
        }

        repository.markHoldReleased(hold.id());
        repository.updateWallet(hold.userId(),
                wallet.availableBalance() + hold.amount(),
                wallet.lockedBalance() - hold.amount());
        repository.insertReleaseLedger(UUID.randomUUID(), hold.userId(), command.releaseOperationId(),
                command.challengeId(), hold.amount());

        ReleaseStakeResult result = new ReleaseStakeResult(command.releaseOperationId(), hold.id(),
                ReleaseOutcome.RELEASED);
        repository.insertOperation(RELEASE_KIND, command.releaseOperationId(), command.challengeId(),
                requestHash, objectMapper.writeValueAsString(result));
        return result;
    }

    private ReleaseStakeResult replay(OperationSnapshot snapshot, byte[] requestHash) {
        if (!MessageDigest.isEqual(snapshot.getRequestHash(), requestHash)) {
            throw new OperationIdConflictException();
        }
        return objectMapper.readValue(snapshot.getResult(), ReleaseStakeResult.class);
    }

    private byte[] requestHash(ReleaseStakeCommand command) {
        return RequestHash.sha256(command.releaseOperationId() + "|" + command.holdId() + "|"
                + command.challengeId() + "|" + command.reason().name());
    }
}
