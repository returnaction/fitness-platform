package io.github.returnaction.fitness.challenge.repository;

import static io.github.returnaction.fitness.challenge.domain.ChallengeOperationKind.JOIN;
import static io.github.returnaction.fitness.challenge.domain.ReleaseReason.CHALLENGE_CANCELLED;
import static io.github.returnaction.fitness.challenge.domain.ReleaseReason.JOIN_NOT_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.challenge.domain.ChallengeOperationStatus;
import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.entity.ChallengeOperationEntity;
import io.github.returnaction.fitness.challenge.entity.ChallengeReleaseIntentEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ChallengeRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ChallengeRepository challenges;

    @Autowired
    private ChallengeOperationRepository operations;

    @Autowired
    private ChallengeReleaseIntentRepository releaseIntents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void pessimisticLockSerializesNextJoinSequence() throws Exception {
        var challenge = newChallenge();
        var template = new TransactionTemplate(transactionManager);
        var ready = new CyclicBarrier(2);
        var firstLocked = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        var sequences = Collections.synchronizedList(new ArrayList<Long>());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                await(ready);
                template.executeWithoutResult(status -> {
                    var locked = challenges.findByIdForUpdate(challenge.getId()).orElseThrow();
                    sequences.add(locked.takeNextJoinSequence(Instant.now()));
                    firstLocked.countDown();
                    await(secondReady);
                });
                return null;
            });
            var second = executor.submit(() -> {
                await(ready);
                await(firstLocked);
                secondReady.countDown();
                template.executeWithoutResult(status -> {
                    var locked = challenges.findByIdForUpdate(challenge.getId()).orElseThrow();
                    sequences.add(locked.takeNextJoinSequence(Instant.now()));
                });
                return null;
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(sequences).containsExactlyInAnyOrder(1L, 2L);
        var stored = challenges.findById(challenge.getId()).orElseThrow();
        assertThat(stored.getNextJoinSequence()).isEqualTo(3);
        assertThat(stored.getVersion()).isEqualTo(3);
    }

    @Test
    void incrementsVersionOnLockedUpdate() {
        var challenge = newChallenge();
        assertThat(challenge.getVersion()).isEqualTo(1);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> challenges
                .findByIdForUpdate(challenge.getId())
                .orElseThrow()
                .takeNextJoinSequence(Instant.now()));

        var updated = challenges.findById(challenge.getId()).orElseThrow();
        assertThat(updated.getNextJoinSequence()).isEqualTo(2);
        assertThat(updated.getVersion()).isEqualTo(2);
    }

    @Test
    void partialUniqueIndexRejectsSecondActiveOperation() {
        var challenge = newChallenge();
        var userId = UUID.randomUUID();
        var first = operations.save(new ChallengeOperationEntity(challenge.getId(), userId, JOIN, Instant.now()));

        var duplicateOperation = new ChallengeOperationEntity(challenge.getId(), userId, JOIN, Instant.now());
        assertThatThrownBy(() -> operations.saveAndFlush(duplicateOperation))
                .isInstanceOf(DataIntegrityViolationException.class);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> operations
                .findById(first.getId())
                .orElseThrow()
                .confirm(UUID.randomUUID(), Instant.now()));
        operations.saveAndFlush(new ChallengeOperationEntity(challenge.getId(), userId, JOIN, Instant.now()));

        assertThat(operations.findByChallengeIdAndStatus(challenge.getId(),
                ChallengeOperationStatus.PENDING)).hasSize(1);
        assertThat(operations.findByChallengeIdAndStatus(challenge.getId(),
                ChallengeOperationStatus.CONFIRMED)).hasSize(1);
    }

    @Test
    void releaseIntentIsUniquePerHold() {
        var challenge = newChallenge();
        var holdId = UUID.randomUUID();
        releaseIntents.save(new ChallengeReleaseIntentEntity(challenge.getId(), holdId, JOIN_NOT_CONFIRMED,
                Instant.now()));

        var duplicateIntent = new ChallengeReleaseIntentEntity(challenge.getId(), holdId, CHALLENGE_CANCELLED,
                Instant.now());
        assertThatThrownBy(() -> releaseIntents.saveAndFlush(duplicateIntent))
                .isInstanceOf(DataIntegrityViolationException.class);

        var stored = releaseIntents.findByHoldId(holdId).orElseThrow();
        assertThat(stored.getReason()).isEqualTo(JOIN_NOT_CONFIRMED);
    }

    private ChallengeEntity newChallenge() {
        return challenges.save(new ChallengeEntity(UUID.randomUUID(), Instant.now()));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test step");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
