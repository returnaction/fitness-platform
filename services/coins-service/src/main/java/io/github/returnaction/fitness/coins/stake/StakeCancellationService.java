package io.github.returnaction.fitness.coins.stake;

import io.github.returnaction.fitness.coins.stake.StakeRepository.OperationSnapshot;
import io.github.returnaction.fitness.coins.stake.exception.ActiveHoldsRemainException;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancellationConflictException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancelledException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSettledException;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Замораживает набор и завершает отмену: новые reserve запрещены, complete
 * проходит только без активных holds.
 */
@Service
@RequiredArgsConstructor
public class StakeCancellationService {

    private static final String FREEZE_KIND = "FREEZE";
    private static final String COMPLETE_KIND = "COMPLETE_CANCELLATION";
    private static final String DRAINING = "CANCEL_DRAINING";
    private static final String CANCELLED = "CANCELLED";
    private static final String SETTLED = "SETTLED";

    private final StakeRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public FreezeStakeSetResult freeze(FreezeStakeSetCommand command) {
        StakeRepository.StakeSetSnapshot set = repository.lockStakeSetSnapshot(command.challengeId());
        byte[] requestHash = requestHash(command);

        var existing = repository.findOperation(FREEZE_KIND, command.cancellationId());
        if (existing.isPresent()) {
            replay(existing.get(), requestHash);
            return new FreezeStakeSetResult(command.cancellationId(), command.challengeId());
        }
        if (SETTLED.equals(set.state())) {
            throw new StakeSetSettledException();
        }
        if (DRAINING.equals(set.state()) || CANCELLED.equals(set.state())) {
            if (!command.cancellationId().equals(set.cancellationId())) {
                throw new OperationIdConflictException();
            }
        } else if (repository.markStakeSetDraining(command.challengeId(), command.cancellationId()) != 1) {
            throw new StakeSetCancelledException();
        }

        FreezeStakeSetResult result = new FreezeStakeSetResult(command.cancellationId(), command.challengeId());
        repository.insertOperation(FREEZE_KIND, command.cancellationId(), command.challengeId(),
                requestHash, objectMapper.writeValueAsString(result));
        return result;
    }

    @Transactional
    public CompleteStakeCancellationResult complete(CompleteStakeCancellationCommand command) {
        StakeRepository.StakeSetSnapshot set = repository.lockStakeSetSnapshot(command.challengeId());
        byte[] requestHash = requestHash(command);

        var existing = repository.findOperation(COMPLETE_KIND, command.cancellationId());
        if (existing.isPresent()) {
            replay(existing.get(), requestHash);
            return new CompleteStakeCancellationResult(command.cancellationId(), command.challengeId());
        }
        if (!DRAINING.equals(set.state())) {
            if (CANCELLED.equals(set.state()) && command.cancellationId().equals(set.cancellationId())) {
                return storeComplete(command, requestHash);
            }
            throw new StakeSetCancellationConflictException();
        }
        if (!command.cancellationId().equals(set.cancellationId())) {
            throw new OperationIdConflictException();
        }
        if (repository.countActiveHolds(command.challengeId()) > 0) {
            throw new ActiveHoldsRemainException();
        }
        if (repository.markStakeSetCancelled(command.challengeId(), command.cancellationId()) != 1) {
            throw new StakeSetCancellationConflictException();
        }
        return storeComplete(command, requestHash);
    }

    private CompleteStakeCancellationResult storeComplete(CompleteStakeCancellationCommand command,
                                                          byte[] requestHash) {
        CompleteStakeCancellationResult result = new CompleteStakeCancellationResult(command.cancellationId(),
                command.challengeId());
        repository.insertOperation(COMPLETE_KIND, command.cancellationId(), command.challengeId(),
                requestHash, objectMapper.writeValueAsString(result));
        return result;
    }

    private void replay(OperationSnapshot snapshot, byte[] requestHash) {
        if (!MessageDigest.isEqual(snapshot.getRequestHash(), requestHash)) {
            throw new OperationIdConflictException();
        }
    }

    private byte[] requestHash(FreezeStakeSetCommand command) {
        return RequestHash.sha256(command.cancellationId() + "|" + command.challengeId());
    }

    private byte[] requestHash(CompleteStakeCancellationCommand command) {
        return RequestHash.sha256(command.cancellationId() + "|" + command.challengeId());
    }
}
