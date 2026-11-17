package io.github.returnaction.fitness.challenge.domain;

import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.ACTIVE;
import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.AWAITING_RESULTS;
import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.CANCELLED;
import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.DRAFT;
import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.FINISHED;
import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChallengeStatusTest {

    @Test
    void allowsMvpLifecycleTransitions() {
        assertThat(DRAFT.canTransitionTo(OPEN)).isTrue();
        assertThat(OPEN.canTransitionTo(ACTIVE)).isTrue();
        assertThat(ACTIVE.canTransitionTo(FINISHED)).isTrue();
        assertThat(OPEN.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    void rejectsTransitionsOutsideTheMvpLifecycle() {
        List<ChallengeStatus> statuses = List.of(DRAFT, OPEN, ACTIVE, AWAITING_RESULTS, FINISHED, CANCELLED);
        for (ChallengeStatus current : statuses) {
            for (ChallengeStatus target : statuses) {
                boolean expected = switch (current) {
                    case DRAFT -> target == OPEN;
                    case OPEN -> target == ACTIVE || target == CANCELLED;
                    case ACTIVE -> target == FINISHED;
                    case AWAITING_RESULTS, FINISHED, CANCELLED -> false;
                };
                assertThat(current.canTransitionTo(target))
                        .as("%s -> %s", current, target)
                        .isEqualTo(expected);
            }
        }
        assertThat(DRAFT.canTransitionTo(null)).isFalse();
    }

    @Test
    void finishedAndCancelledAreTerminal() {
        assertThat(FINISHED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(DRAFT.isTerminal()).isFalse();
        assertThat(OPEN.isTerminal()).isFalse();
        assertThat(ACTIVE.isTerminal()).isFalse();
        assertThat(AWAITING_RESULTS.isTerminal()).isFalse();
    }

    @Test
    void requireTransitionToReportsInvalidTransition() {
        DRAFT.requireTransitionTo(OPEN);

        assertThatThrownBy(() -> DRAFT.requireTransitionTo(ACTIVE))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("ACTIVE")
                .hasFieldOrPropertyWithValue("currentStatus", "DRAFT")
                .hasFieldOrPropertyWithValue("targetStatus", "ACTIVE");
        assertThatThrownBy(() -> FINISHED.requireTransitionTo(OPEN))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
