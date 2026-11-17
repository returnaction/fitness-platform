package io.github.returnaction.fitness.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.outbox.ChallengeEventPublisher;
import io.github.returnaction.fitness.challenge.outbox.ChallengeOutboxPublisher;
import io.github.returnaction.fitness.challenge.outbox.ChallengeOutboxService;
import io.github.returnaction.fitness.challenge.repository.ChallengeOutboxRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ChallengeOutboxIntegrationTest {

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
    private ChallengeOutboxService outbox;

    @Autowired
    private ChallengeOutboxRepository outboxRepository;

    @Autowired
    private ChallengeOutboxPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ChallengeEventPublisher eventPublisher;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM challenge_outbox");
    }

    @Test
    void commitsBusinessChangeAndEventTogetherOrRollsBackBoth() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        var transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            challenges.save(new ChallengeEntity(userId, Instant.now()));
            outbox.append(eventId, "challenge.finished", 1, aggregateId, "{\"challengeId\":\"" + aggregateId + "\"}");
        });
        assertThat(count("SELECT count(*) FROM challenge_outbox WHERE event_id = ?", eventId)).isEqualTo(1);

        UUID failedEventId = UUID.randomUUID();
        UUID failedAggregateId = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            challenges.save(new ChallengeEntity(UUID.randomUUID(), Instant.now()));
            outbox.append(failedEventId, "challenge.finished", 1, failedAggregateId,
                    "{\"challengeId\":\"" + failedAggregateId + "\"}");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM challenge_outbox WHERE event_id = ?", failedEventId)).isZero();
    }

    @Test
    void publishesOnceAndMarksEventAsPublished() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outbox.append(
                eventId, "challenge.finished", 1, aggregateId, "{\"challengeId\":\"" + aggregateId + "\"}"));
        assertThat(outboxRepository.countUnpublished()).isEqualTo(1);

        publisher.publish();

        verify(eventPublisher, times(1)).publish(any(), any(), any(), any());
        assertThat(outboxRepository.countUnpublished()).isZero();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM challenge_outbox WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();

        publisher.publish();
        verify(eventPublisher, times(1)).publish(any(), any(), any(), any());
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args),
                "count query must return a value");
    }
}
