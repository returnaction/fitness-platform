package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.operation.RegistrationOperationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RegistrationReadinessRepository extends Repository<RegistrationOperationEntity, UUID> {

    @Query(value = "SELECT status FROM registration_operations WHERE id = :id", nativeQuery = true)
    Optional<String> findStatusValue(@Param("id") UUID operationId);

    default String findStatus(UUID operationId) {
        return findStatusValue(operationId)
                .orElseThrow(() -> new IllegalStateException("Registration operation is missing"));
    }
}
