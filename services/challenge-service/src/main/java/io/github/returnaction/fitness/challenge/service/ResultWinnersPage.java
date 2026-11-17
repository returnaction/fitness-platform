package io.github.returnaction.fitness.challenge.service;

import java.util.List;
import java.util.UUID;

public record ResultWinnersPage(List<UUID> userIds, String nextCursor) {
}
