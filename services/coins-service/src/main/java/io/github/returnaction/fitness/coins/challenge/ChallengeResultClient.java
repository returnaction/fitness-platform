package io.github.returnaction.fitness.coins.challenge;

import java.util.List;
import java.util.UUID;

/**
 * Чтение неизменяемого списка победителей у challenge-service по mTLS.
 */
public interface ChallengeResultClient {

    List<UUID> listWinners(UUID resultId);
}
