package io.github.returnaction.fitness.coins.wallet.repository;

import io.github.returnaction.fitness.coins.wallet.entity.WalletEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from WalletEntity wallet where wallet.userId = :userId")
    Optional<WalletEntity> findByIdForUpdate(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            INSERT INTO coin_wallets (user_id) VALUES (:userId)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId);
}
