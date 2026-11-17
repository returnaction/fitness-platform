package io.github.returnaction.fitness.challenge.domain;

import static io.github.returnaction.fitness.challenge.domain.ParticipationStatus.ACTIVE;
import static io.github.returnaction.fitness.challenge.domain.ParticipationStatus.COMPLETED;
import static io.github.returnaction.fitness.challenge.domain.ParticipationStatus.JOINED;
import static io.github.returnaction.fitness.challenge.domain.ParticipationStatus.WINNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParticipationStatusTest {

    @Test
    void allowsStartAndResultTransitions() {
        assertThat(JOINED.canTransitionTo(ACTIVE)).isTrue();
        assertThat(ACTIVE.canTransitionTo(WINNER)).isTrue();
        assertThat(ACTIVE.canTransitionTo(COMPLETED)).isTrue();
    }

    @Test
    void rejectsSkippingOrRepeatingStates() {
        List<ParticipationStatus> statuses = List.of(JOINED, ACTIVE, WINNER, COMPLETED);
        for (ParticipationStatus current : statuses) {
            for (ParticipationStatus target : statuses) {
                boolean expected = switch (current) {
                    case JOINED -> target == ACTIVE;
                    case ACTIVE -> target == WINNER || target == COMPLETED;
                    case WINNER, COMPLETED -> false;
                };
                assertThat(current.canTransitionTo(target))
                        .as("%s -> %s", current, target)
                        .isEqualTo(expected);
            }
        }
        assertThat(JOINED.canTransitionTo(null)).isFalse();
    }

    @Test
    void winnerAndCompletedAreTerminal() {
        assertThat(WINNER.isTerminal()).isTrue();
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(JOINED.isTerminal()).isFalse();
        assertThat(ACTIVE.isTerminal()).isFalse();
    }

    @Test
    void requireTransitionToReportsInvalidTransition() {
        JOINED.requireTransitionTo(ACTIVE);

        assertThatThrownBy(() -> JOINED.requireTransitionTo(WINNER))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("JOINED")
                .hasMessageContaining("WINNER")
                .hasFieldOrPropertyWithValue("currentStatus", "JOINED")
                .hasFieldOrPropertyWithValue("targetStatus", "WINNER");
    }
}
