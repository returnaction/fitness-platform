package io.github.returnaction.fitness.coins.stake;

import io.github.returnaction.fitness.coins.stake.StakeRepository.OperationSnapshot;
import io.github.returnaction.fitness.coins.stake.exception.DuplicateStakeEntryException;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancelledException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetIncompleteException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetMismatchException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealInProgressException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealNotFoundException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealedException;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Пакетное закрытие набора: Begin сохраняет ожидаемые параметры, Put принимает
 * строки, Finalize сверяет count/digest и активные holds, затем фиксирует SEALED.
 */
@Service
@RequiredArgsConstructor
public class StakeSetService {

    private static final String SEAL_KIND = "SEAL";
    private static final String OPEN = "OPEN";
    private static final String UPLOADING = "UPLOADING";
    private static final String SEALED = "SEALED";

    private final StakeRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public BeginStakeSetResult begin(BeginStakeSetCommand command) {
        String state = repository.lockStakeSet(command.challengeId());

        var existing = repository.findUpload(command.sealOperationId());
        if (existing.isPresent()) {
            if (!matches(existing.get(), command)) {
                throw new OperationIdConflictException();
            }
            return new BeginStakeSetResult(command.sealOperationId());
        }

        requireOpen(state);
        if (repository.findActiveUpload(command.challengeId()).isPresent()) {
            throw new StakeSetSealInProgressException();
        }

        repository.insertUpload(command.sealOperationId(), command.challengeId(), command.setVersion(),
                command.expectedCount(), StakeDigest.fromHex(command.expectedDigestHex()));
        return new BeginStakeSetResult(command.sealOperationId());
    }

    @Transactional
    public PutStakeBatchResult putBatch(PutStakeBatchCommand command) {
        var known = repository.findUpload(command.sealOperationId())
                .orElseThrow(StakeSetSealNotFoundException::new);
        String uploadState = repository.lockStakeSet(known.challengeId());
        var upload = repository.lockUpload(command.sealOperationId())
                .orElseThrow(StakeSetSealNotFoundException::new);
        if (SEALED.equals(upload.status())) {
            throw new StakeSetSealedException();
        }
        requireOpen(uploadState);

        byte[] payloadDigest = StakeDigest.digest(command.stakes());
        String payloadDigestHex = StakeDigest.toHex(payloadDigest);
        var existingBatch = repository.findBatch(command.sealOperationId(), command.batchNumber());
        if (existingBatch.isPresent()) {
            if (!existingBatch.get().payloadDigestHex().equals(payloadDigestHex)) {
                throw new OperationIdConflictException();
            }
            return new PutStakeBatchResult(command.sealOperationId(), command.batchNumber());
        }

        try {
            repository.insertBatch(command.sealOperationId(), command.batchNumber(), payloadDigest,
                    command.stakes().size());
            for (SealedStake stake : command.stakes()) {
                repository.insertSealItem(command.sealOperationId(), command.batchNumber(), stake);
            }
        } catch (DuplicateKeyException _) {
            throw new DuplicateStakeEntryException();
        }
        return new PutStakeBatchResult(command.sealOperationId(), command.batchNumber());
    }

    @Transactional
    public FinalizeStakeSetResult finalizeSet(FinalizeStakeSetCommand command) {
        var known = repository.findUpload(command.sealOperationId())
                .orElseThrow(StakeSetSealNotFoundException::new);
        String state = repository.lockStakeSet(known.challengeId());
        var upload = repository.lockUpload(command.sealOperationId())
                .orElseThrow(StakeSetSealNotFoundException::new);

        if (SEALED.equals(upload.status())) {
            return replay(command.sealOperationId());
        }
        requireOpen(state);

        List<SealedStake> items = repository.findSealItems(command.sealOperationId());
        byte[] expectedDigest = StakeDigest.fromHex(upload.expectedDigestHex());
        if (items.size() != upload.expectedCount()
                || !MessageDigest.isEqual(StakeDigest.digest(items), expectedDigest)) {
            throw new StakeSetIncompleteException();
        }

        List<SealedStake> active = repository.findActiveStakes(upload.challengeId());
        if (active.size() != items.size()
                || !MessageDigest.isEqual(StakeDigest.digest(active), StakeDigest.digest(items))) {
            throw new StakeSetMismatchException();
        }

        if (repository.sealStakeSet(upload.challengeId(), upload.setVersion(), expectedDigest) != 1) {
            throw new StakeSetSealedException();
        }
        repository.markUploadSealed(command.sealOperationId());

        FinalizeStakeSetResult result = new FinalizeStakeSetResult(command.sealOperationId(),
                upload.challengeId(), upload.setVersion(), items.size(), upload.expectedDigestHex());
        repository.insertOperation(SEAL_KIND, command.sealOperationId(), upload.challengeId(),
                sealHash(upload), objectMapper.writeValueAsString(result));
        return result;
    }

    private FinalizeStakeSetResult replay(UUID sealOperationId) {
        OperationSnapshot snapshot = repository.findOperation(SEAL_KIND, sealOperationId)
                .orElseThrow(StakeSetSealNotFoundException::new);
        return objectMapper.readValue(snapshot.getResult(), FinalizeStakeSetResult.class);
    }

    private void requireOpen(String state) {
        if (SEALED.equals(state) || "SETTLED".equals(state)) {
            throw new StakeSetSealedException();
        }
        if ("CANCEL_DRAINING".equals(state) || "CANCELLED".equals(state)) {
            throw new StakeSetCancelledException();
        }
    }

    private boolean matches(StakeRepository.UploadSnapshot upload, BeginStakeSetCommand command) {
        return upload.challengeId().equals(command.challengeId())
                && upload.setVersion() == command.setVersion()
                && upload.expectedCount() == command.expectedCount()
                && upload.expectedDigestHex().equals(command.expectedDigestHex());
    }

    private byte[] sealHash(StakeRepository.UploadSnapshot upload) {
        return RequestHash.sha256(upload.sealOperationId() + "|" + upload.challengeId() + "|"
                + upload.setVersion() + "|" + upload.expectedCount() + "|" + upload.expectedDigestHex());
    }
}
