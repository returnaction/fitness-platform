package io.github.returnaction.fitness.coins.stake;

import static io.github.returnaction.fitness.coins.stake.ReleaseReason.CHALLENGE_CANCELLED;
import static io.github.returnaction.fitness.coins.stake.ReleaseReason.JOIN_NOT_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.coins.stake.exception.HoldNotReleasableException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancelledException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetMismatchException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealedException;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class StakeConcurrencyIntegrationTest {

    private static final long STAKE = 10;

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
    private StakeCancellationService cancellations;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentDoubleReleaseRestoresBalanceOnce() throws Exception {
        UUID userId = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        UUID holdId = reserve(userId, challengeId);

        Outcome<ReleaseStakeResult> first;
        Outcome<ReleaseStakeResult> second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Future<Outcome<ReleaseStakeResult>> firstFuture = executor.submit(() -> call(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return releases.release(new ReleaseStakeCommand(UUID.randomUUID(), holdId, challengeId,
                        JOIN_NOT_CONFIRMED));
            }));
            Future<Outcome<ReleaseStakeResult>> secondFuture = executor.submit(() -> call(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return releases.release(new ReleaseStakeCommand(UUID.randomUUID(), holdId, challengeId,
                        CHALLENGE_CANCELLED));
            }));
            first = await(firstFuture);
            second = await(secondFuture);
        }

        Outcome<ReleaseStakeResult> success = first.succeeded() ? first : second;
        Outcome<ReleaseStakeResult> rejected = first.succeeded() ? second : first;
        assertThat(success.value().outcome()).isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(rejected.error()).isInstanceOf(HoldNotReleasableException.class);
        assertThat(wallet(userId)).containsExactly(100L, 0L);
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM coin_ledger_entries WHERE type = 'RELEASE' AND user_id = ?",
                userId)).isEqualTo(1L);
    }

    @Test
    void concurrentReserveAndFinalizeKeepSealedSetConsistent() throws Exception {
        UUID challengeId = UUID.randomUUID();
        UUID firstUser = createWallet(100);
        UUID secondUser = createWallet(100);
        UUID thirdUser = createWallet(100);
        UUID firstHold = reserve(firstUser, challengeId);
        UUID secondHold = reserve(secondUser, challengeId);
        List<SealedStake> stakes = List.of(
                new SealedStake(firstHold, firstUser, STAKE),
                new SealedStake(secondHold, secondUser, STAKE));
        UUID sealId = UUID.randomUUID();
        stakeSets.begin(new BeginStakeSetCommand(sealId, challengeId, 1, stakes.size(),
                StakeDigest.toHex(StakeDigest.digest(stakes))));
        stakeSets.putBatch(new PutStakeBatchCommand(sealId, 0, stakes));

        Outcome<FinalizeStakeSetResult> seal;
        Outcome<ReserveStakeResult> reserve;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Future<Outcome<FinalizeStakeSetResult>> sealFuture = executor.submit(() -> call(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return stakeSets.finalizeSet(new FinalizeStakeSetCommand(sealId));
            }));
            Future<Outcome<ReserveStakeResult>> reserveFuture = executor.submit(() -> call(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reservations.reserve(new ReserveStakeCommand(UUID.randomUUID(), thirdUser,
                        challengeId, STAKE));
            }));
            seal = await(sealFuture);
            reserve = await(reserveFuture);
        }

        if (seal.succeeded()) {
            assertThat(reserve.error()).isInstanceOf(StakeSetSealedException.class);
            assertThat(stakeSetState(challengeId)).isEqualTo("SEALED");
            assertThat(activeHolds(challengeId)).isEqualTo(2L);
            assertThat(count("""
                    SELECT count(*) FROM coin_sealed_stakes
                    WHERE challenge_id = ? AND set_version = 1
                    """, challengeId)).isEqualTo(2L);
            assertThat(wallet(thirdUser)).containsExactly(100L, 0L);
        } else {
            assertThat(seal.error()).isInstanceOf(StakeSetMismatchException.class);
            assertThat(reserve.succeeded()).isTrue();
            assertThat(reserve.value().outcome()).isEqualTo(ReserveOutcome.RESERVED);
            assertThat(stakeSetState(challengeId)).isEqualTo("OPEN");
            assertThat(activeHolds(challengeId)).isEqualTo(3L);
            assertThat(count("""
                    SELECT count(*) FROM coin_sealed_stakes
                    WHERE challenge_id = ? AND set_version = 1
                    """, challengeId)).isZero();
            assertThat(wallet(thirdUser)).containsExactly(90L, STAKE);
        }
    }

    @Test
    void concurrentFreezeAndReserveNeverLoseFunds() throws Exception {
        UUID challengeId = UUID.randomUUID();
        UUID owner = createWallet(100);
        UUID other = createWallet(100);
        reserve(owner, challengeId);
        UUID cancellationId = UUID.randomUUID();

        Outcome<FreezeStakeSetResult> freeze;
        Outcome<ReserveStakeResult> reserve;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Future<Outcome<FreezeStakeSetResult>> freezeFuture = executor.submit(() -> call(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return cancellations.freeze(new FreezeStakeSetCommand(cancellationId, challengeId));
            }));
            Future<Outcome<ReserveStakeResult>> reserveFuture = executor.submit(() -> call(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reservations.reserve(new ReserveStakeCommand(UUID.randomUUID(), other,
                        challengeId, STAKE));
            }));
            freeze = await(freezeFuture);
            reserve = await(reserveFuture);
        }

        assertThat(freeze.succeeded()).isTrue();
        assertThat(freeze.value()).isEqualTo(new FreezeStakeSetResult(cancellationId, challengeId));
        assertThat(stakeSetState(challengeId)).isEqualTo("CANCEL_DRAINING");
        if (reserve.succeeded()) {
            assertThat(reserve.value().outcome()).isEqualTo(ReserveOutcome.RESERVED);
            assertThat(activeHolds(challengeId)).isEqualTo(2L);
            assertThat(wallet(other)).containsExactly(90L, STAKE);
        } else {
            assertThat(reserve.error()).isInstanceOf(StakeSetCancelledException.class);
            assertThat(activeHolds(challengeId)).isEqualTo(1L);
            assertThat(wallet(other)).containsExactly(100L, 0L);
        }
    }

    private UUID createWallet(long available) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO coin_wallets (user_id, available_balance) VALUES (?, ?)", userId, available);
        return userId;
    }

    private UUID reserve(UUID userId, UUID challengeId) {
        ReserveStakeResult result = reservations.reserve(new ReserveStakeCommand(UUID.randomUUID(),
                userId, challengeId, STAKE));
        return result.holdId();
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM coin_holds WHERE id = ?", String.class, holdId);
    }

    private String stakeSetState(UUID challengeId) {
        return jdbc.queryForObject("SELECT state FROM coin_stake_sets WHERE challenge_id = ?",
                String.class, challengeId);
    }

    private long activeHolds(UUID challengeId) {
        return count("SELECT count(*) FROM coin_holds WHERE challenge_id = ? AND status = 'ACTIVE'",
                challengeId);
    }

    private List<Long> wallet(UUID userId) {
        return jdbc.query("SELECT available_balance, locked_balance FROM coin_wallets WHERE user_id = ?",
                (resultSet, rowNumber) -> List.of(
                        resultSet.getLong("available_balance"),
                        resultSet.getLong("locked_balance")),
                userId).getFirst();
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args),
                "count query must return a value");
    }

    private static <T> Outcome<T> call(Action<T> action) {
        try {
            return new Outcome<>(action.run(), null);
        } catch (Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private static <T> Outcome<T> await(Future<Outcome<T>> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Concurrent operation did not finish", exception);
        }
    }

    @FunctionalInterface
    private interface Action<T> {

        T run() throws Exception;
    }

    private record Outcome<T>(T value, Throwable error) {

        boolean succeeded() {
            return error == null;
        }
    }
}
