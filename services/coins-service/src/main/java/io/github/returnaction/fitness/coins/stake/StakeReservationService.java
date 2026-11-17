package io.github.returnaction.fitness.coins.stake;

import io.github.returnaction.fitness.coins.stake.StakeRepository.OperationSnapshot;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.WalletNotFoundException;
import java.security.MessageDigest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Резервирует ставку одной транзакцией: проверяет активный hold, блокирует
 * кошелёк, переносит сумму в locked, создаёт hold, ledger и сохранённый исход.
 */
@Service
@RequiredArgsConstructor
public class StakeReservationService {

    private static final String RESERVE_KIND = "RESERVE";

    private final StakeRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReserveStakeResult reserve(ReserveStakeCommand command) {
        repository.lockStakeSet(command.challengeId());

        byte[] requestHash = requestHash(command);
        var existing = repository.findOperation(RESERVE_KIND, command.joinOperationId());
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        if (repository.findActiveHold(command.challengeId(), command.userId()).isPresent()) {
            return store(command, requestHash, new ReserveStakeResult(command.joinOperationId(),
                    ReserveOutcome.ACTIVE_HOLD_EXISTS, null));
        }

        var wallet = repository.lockWallet(command.userId()).orElseThrow(WalletNotFoundException::new);
        if (wallet.availableBalance() < command.amount()) {
            return store(command, requestHash, new ReserveStakeResult(command.joinOperationId(),
                    ReserveOutcome.INSUFFICIENT_FUNDS, null));
        }

        UUID holdId = UUID.randomUUID();
        repository.insertHold(holdId, command.userId(), command.challengeId(), command.joinOperationId(),
                command.amount());
        repository.updateWallet(command.userId(),
                wallet.availableBalance() - command.amount(),
                wallet.lockedBalance() + command.amount());
        repository.insertReserveLedger(UUID.randomUUID(), command.userId(), command.joinOperationId(),
                command.challengeId(), command.amount());

        return store(command, requestHash, new ReserveStakeResult(command.joinOperationId(),
                ReserveOutcome.RESERVED, holdId));
    }

    private ReserveStakeResult replay(OperationSnapshot snapshot, byte[] requestHash) {
        if (!MessageDigest.isEqual(snapshot.getRequestHash(), requestHash)) {
            throw new OperationIdConflictException();
        }
        return objectMapper.readValue(snapshot.getResult(), ReserveStakeResult.class);
    }

    private ReserveStakeResult store(ReserveStakeCommand command, byte[] requestHash,
                                     ReserveStakeResult result) {
        repository.insertOperation(RESERVE_KIND, command.joinOperationId(), command.challengeId(), requestHash,
                objectMapper.writeValueAsString(result));
        return result;
    }

    private byte[] requestHash(ReserveStakeCommand command) {
        return RequestHash.sha256(command.joinOperationId() + "|" + command.userId() + "|"
                + command.challengeId() + "|" + command.amount());
    }
}
