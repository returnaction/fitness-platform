package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.RegistrationOperationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationOperationRepository extends JpaRepository<RegistrationOperationEntity, UUID> {

    Optional<RegistrationOperationEntity> findByIdempotencyKeyDigest(byte[] idempotencyKeyDigest);
}
