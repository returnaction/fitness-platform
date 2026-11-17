package io.github.returnaction.fitness.coins.stake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.stake.exception.DuplicateStakeEntryException;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetIncompleteException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetMismatchException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealInProgressException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealedException;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class StakeSetServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private SecureGrpcServer grpcServer;

    @Autowired
    private StakeReservationService reservations;

    @Autowired
    private ReleaseStakeService releases;

    @Autowired
    private StakeSetService stakeSets;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sealsSetReplaysFinalizeAndBlocksNewReserve() {
        UUID challengeId = UUID.randomUUID();
        List<SealedStake> stakes = reservePair(challengeId);
        UUID sealId = UUID.randomUUID();
        var begin = new BeginStakeSetCommand(sealId, challengeId, 1, stakes.size(), digest(stakes));

        assertThat(stakeSets.begin(begin).sealOperationId()).isEqualTo(sealId);
        stakeSets.putBatch(new PutStakeBatchCommand(sealId, 0, stakes));
        FinalizeStakeSetResult result = stakeSets.finalizeSet(new FinalizeStakeSetCommand(sealId));

        assertThat(result.stakeCount()).isEqualTo(2);
        assertThat(result.setDigestHex()).isEqualTo(digest(stakes));
        assertThat(stakeSetState(challengeId)).isEqualTo("SEALED");
        assertThat(uploadStatus(sealId)).isEqualTo("SEALED");
        assertThat(count("SELECT count(*) FROM coin_stake_operations WHERE kind = 'SEAL' AND id = ?", sealId))
                .isEqualTo(1);

        assertThat(stakeSets.finalizeSet(new FinalizeStakeSetCommand(sealId))).isEqualTo(result);

        UUID thirdUser = createWallet(100);
        var blocked = new ReserveStakeCommand(UUID.randomUUID(), thirdUser, challengeId, 10);
        assertThatThrownBy(() -> reservations.reserve(blocked))
                .isInstanceOf(StakeSetSealedException.class);
    }

    @Test
    void rejectsIncompleteSetBeforeSealing() {
        UUID challengeId = UUID.randomUUID();
        List<SealedStake> stakes = reservePair(challengeId);
        UUID sealId = UUID.randomUUID();
        stakeSets.begin(new BeginStakeSetCommand(sealId, challengeId, 1, 1, digest(stakes)));
        stakeSets.putBatch(new PutStakeBatchCommand(sealId, 0, stakes));

        var finalize = new FinalizeStakeSetCommand(sealId);
        assertThatThrownBy(() -> stakeSets.finalizeSet(finalize))
                .isInstanceOf(StakeSetIncompleteException.class);
        assertThat(stakeSetState(challengeId)).isEqualTo("OPEN");
    }

    @Test
    void rejectsMismatchWithActiveHolds() {
        UUID challengeId = UUID.randomUUID();
        List<SealedStake> stakes = reservePair(challengeId);
        UUID sealId = UUID.randomUUID();
        stakeSets.begin(new BeginStakeSetCommand(sealId, challengeId, 1, stakes.size(), digest(stakes)));
        stakeSets.putBatch(new PutStakeBatchCommand(sealId, 0, stakes));
        releases.release(new ReleaseStakeCommand(UUID.randomUUID(), stakes.getFirst().holdId(), challengeId,
                ReleaseReason.JOIN_NOT_CONFIRMED));

        var finalize = new FinalizeStakeSetCommand(sealId);
        assertThatThrownBy(() -> stakeSets.finalizeSet(finalize))
                .isInstanceOf(StakeSetMismatchException.class);
        assertThat(stakeSetState(challengeId)).isEqualTo("OPEN");
    }

    @Test
    void rejectsDuplicateBatchAndChangedBatchPayload() {
        UUID challengeId = UUID.randomUUID();
        List<SealedStake> stakes = reservePair(challengeId);
        UUID sealId = UUID.randomUUID();
        stakeSets.begin(new BeginStakeSetCommand(sealId, challengeId, 1, stakes.size(), digest(stakes)));
        stakeSets.putBatch(new PutStakeBatchCommand(sealId, 0, List.of(stakes.getFirst())));

        var duplicate = new PutStakeBatchCommand(sealId, 1, List.of(stakes.getFirst()));
        assertThatThrownBy(() -> stakeSets.putBatch(duplicate))
                .isInstanceOf(DuplicateStakeEntryException.class);

        var changed = new PutStakeBatchCommand(sealId, 0, List.of(stakes.getLast()));
        assertThatThrownBy(() -> stakeSets.putBatch(changed))
                .isInstanceOf(OperationIdConflictException.class);
    }

    @Test
    void replaysBeginAndRejectsChangedParametersOrParallelSeal() {
        UUID challengeId = UUID.randomUUID();
        List<SealedStake> stakes = reservePair(challengeId);
        UUID sealId = UUID.randomUUID();
        var begin = new BeginStakeSetCommand(sealId, challengeId, 1, stakes.size(), digest(stakes));
        stakeSets.begin(begin);
        assertThat(stakeSets.begin(begin).sealOperationId()).isEqualTo(sealId);

        var changed = new BeginStakeSetCommand(sealId, challengeId, 1, stakes.size() + 1, digest(stakes));
        assertThatThrownBy(() -> stakeSets.begin(changed))
                .isInstanceOf(OperationIdConflictException.class);

        var parallel = new BeginStakeSetCommand(UUID.randomUUID(), challengeId, 1, stakes.size(), digest(stakes));
        assertThatThrownBy(() -> stakeSets.begin(parallel))
                .isInstanceOf(StakeSetSealInProgressException.class);
    }

    @Test
    void rejectsOversizedBatch() {
        List<SealedStake> oversized = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            oversized.add(new SealedStake(UUID.randomUUID(), UUID.randomUUID(), 1));
        }
        UUID sealId = UUID.randomUUID();

        assertThatThrownBy(() -> new PutStakeBatchCommand(sealId, 0, oversized))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<SealedStake> reservePair(UUID challengeId) {
        UUID firstUser = createWallet(100);
        UUID secondUser = createWallet(100);
        UUID firstHold = reservations
                .reserve(new ReserveStakeCommand(UUID.randomUUID(), firstUser, challengeId, 30))
                .holdId();
        UUID secondHold = reservations
                .reserve(new ReserveStakeCommand(UUID.randomUUID(), secondUser, challengeId, 30))
                .holdId();
        return List.of(new SealedStake(firstHold, firstUser, 30), new SealedStake(secondHold, secondUser, 30));
    }

    private UUID createWallet(long available) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO coin_wallets (user_id, available_balance) VALUES (?, ?)", userId, available);
        return userId;
    }

    private String digest(List<SealedStake> stakes) {
        return StakeDigest.toHex(StakeDigest.digest(stakes));
    }

    private String stakeSetState(UUID challengeId) {
        return jdbc.queryForObject("SELECT state FROM coin_stake_sets WHERE challenge_id = ?", String.class,
                challengeId);
    }

    private String uploadStatus(UUID sealId) {
        return jdbc.queryForObject("SELECT status FROM coin_stake_seal_uploads WHERE seal_operation_id = ?",
                String.class, sealId);
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args),
                "count query must return a value");
    }
}
