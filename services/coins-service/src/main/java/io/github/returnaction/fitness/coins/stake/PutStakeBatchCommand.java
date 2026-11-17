package io.github.returnaction.fitness.coins.stake;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record PutStakeBatchCommand(UUID sealOperationId, int batchNumber, List<SealedStake> stakes) {

    public PutStakeBatchCommand {
        Objects.requireNonNull(sealOperationId, "sealOperationId");
        Objects.requireNonNull(stakes, "stakes");
        if (batchNumber < 0) {
            throw new IllegalArgumentException("batchNumber must not be negative");
        }
        if (stakes.isEmpty() || stakes.size() > 500) {
            throw new IllegalArgumentException("batch must contain 1..500 stakes");
        }
        stakes = List.copyOf(stakes);
        Set<UUID> holds = new HashSet<>();
        Set<UUID> users = new HashSet<>();
        for (SealedStake stake : stakes) {
            if (!holds.add(stake.holdId()) || !users.add(stake.userId())) {
                throw new IllegalArgumentException("batch must contain unique hold and user IDs");
            }
        }
    }
}
