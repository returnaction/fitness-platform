package io.github.returnaction.fitness.accounts.profile.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileCreationExhaustedException;
import io.github.returnaction.fitness.accounts.profile.helpers.NicknameGenerator;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт профиль один раз и не изменяет уже существующий. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repository is shared and not exposed.")
public class ProfileCreationService {

    private static final int MAX_ATTEMPTS = 20;
    private final ProfileRepository profileRepository;
    private final NicknameGenerator nicknameGenerator;

    @Transactional
    public void createIfAbsent(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            UUID publicProfileId = nextPublicProfileId(userId);
            String nickname = nicknameGenerator.generate();

            if (profileRepository.insertIfAbsent(userId, publicProfileId, nickname) == 1) {
                return;
            }

            if (profileRepository.existsById(userId)) {
                return;
            }
        }
        throw new ProfileCreationExhaustedException();
    }

    private UUID nextPublicProfileId(UUID userId) {
        UUID publicProfileId;
        do {
            publicProfileId = UUID.randomUUID();
        } while (publicProfileId.equals(userId));
        return publicProfileId;
    }
}
