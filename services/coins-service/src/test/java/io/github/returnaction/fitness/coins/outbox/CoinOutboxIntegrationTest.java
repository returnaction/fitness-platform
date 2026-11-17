package io.github.returnaction.fitness.coins.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CoinOutboxIntegrationTest {

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

    @MockitoBean
    private CoinEventPublisher eventPublisher;

    @Autowired
    private CoinOutboxRepository repository;

    @Autowired
    private CoinOutboxPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM coin_outbox");
        clearInvocations(eventPublisher);
    }

    @Test
    void publishesOnceAndMarksEventAsPublished() {
        UUID eventId = insertEvent();

        publisher.publish();

        verify(eventPublisher, times(1)).publish(eq(eventId), eq("coins.settlement_completed"),
                any(), any());
        assertThat(repository.countUnpublished()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM coin_outbox WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();

        publisher.publish();
        verify(eventPublisher, times(1)).publish(eq(eventId), eq("coins.settlement_completed"),
                any(), any());
    }

    @Test
    void leasePreventsConcurrentClaimUntilCleared() {
        UUID eventId = insertEvent();

        assertThat(repository.claim(10, 60)).hasSize(1);
        assertThat(repository.claim(10, 60)).isEmpty();
        assertThat(leaseGeneration(eventId)).isEqualTo(1L);

        repository.clearLease(eventId);

        assertThat(repository.claim(10, 60)).hasSize(1);
        assertThat(leaseGeneration(eventId)).isEqualTo(2L);
    }

    @Test
    void failedPublishKeepsEventAndClearsLease() {
        UUID eventId = insertEvent();
        doThrow(new OutboxPublishException(new IllegalStateException("broker")))
                .when(eventPublisher).publish(any(), any(), any(), any());

        publisher.publish();

        assertThat(repository.countUnpublished()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT lease_until IS NULL FROM coin_outbox WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();
    }

    private UUID insertEvent() {
        UUID eventId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        repository.insert(eventId, "coins.settlement_completed", 1, challengeId,
                "{\"event_type\":\"coins.settlement_completed\",\"data\":{\"challengeId\":\""
                        + challengeId + "\"}}");
        return eventId;
    }

    private long leaseGeneration(UUID eventId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT lease_generation FROM coin_outbox WHERE event_id = ?",
                Long.class, eventId));
    }
}
