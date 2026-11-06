package io.github.returnaction.fitness.accounts.profile.service;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт профиль один раз и не изменяет уже существующий. */
@Service
public class ProfileProvisioningService {

    private static final int MAX_ATTEMPTS = 20;
    private final JdbcClient jdbcClient;
    private final NicknameGenerator nicknameGenerator;

    public ProfileProvisioningService(JdbcClient jdbcClient, NicknameGenerator nicknameGenerator) {
        this.jdbcClient = jdbcClient;
        this.nicknameGenerator = nicknameGenerator;
    }

    @Transactional
    public void ensureProfile(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            UUID publicId = UUID.randomUUID();
            if (publicId.equals(userId)) {
                continue;
            }
            String nickname = nicknameGenerator.generate();
            int inserted = jdbcClient
                    .sql("""
                    INSERT INTO user_profiles (user_id, public_profile_id, nickname)
                    VALUES (:userId, :publicId, :nickname)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("userId", userId)
                    .param("publicId", publicId)
                    .param("nickname", nickname)
                    .update();
            if (inserted == 1 || profileExists(userId)) {
                return;
            }
        }
        throw new IllegalStateException("Could not allocate a unique profile identifier");
    }

    private boolean profileExists(UUID userId) {
        return jdbcClient
                .sql("SELECT EXISTS (SELECT 1 FROM user_profiles WHERE user_id = :userId)")
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }
}
