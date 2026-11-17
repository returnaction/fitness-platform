package io.github.returnaction.fitness.coins.wallet.repository;

import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {
}
