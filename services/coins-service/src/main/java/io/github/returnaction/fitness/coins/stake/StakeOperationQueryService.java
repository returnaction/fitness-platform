package io.github.returnaction.fitness.coins.stake;

import io.github.returnaction.fitness.coins.stake.exception.OperationNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Возвращает сохранённый исход reserve/release для разрешения потерянных ответов.
 */
@Service
@RequiredArgsConstructor
public class StakeOperationQueryService {

    private static final String RESERVE_KIND = "RESERVE";
    private static final String RELEASE_KIND = "RELEASE";

    private final StakeRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ReserveStakeResult reserve(UUID operationId) {
        var snapshot = repository.findOperation(RESERVE_KIND, operationId)
                .orElseThrow(OperationNotFoundException::new);
        return objectMapper.readValue(snapshot.getResult(), ReserveStakeResult.class);
    }

    @Transactional(readOnly = true)
    public ReleaseStakeResult release(UUID operationId) {
        var snapshot = repository.findOperation(RELEASE_KIND, operationId)
                .orElseThrow(OperationNotFoundException::new);
        return objectMapper.readValue(snapshot.getResult(), ReleaseStakeResult.class);
    }
}
