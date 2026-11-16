package io.github.returnaction.fitness.coins.dailyreward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryEntity;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryType;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.coins.wallet.repository.LedgerEntryRepository;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DailyRewardClaimHttpTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:34:56Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @MockitoBean
    private SecureGrpcServer managedGrpcServer;

    @MockitoBean
    private Clock clock;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private LedgerEntryRepository ledgerEntries;

    @Autowired
    private DailyRewardClaimRepository claims;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private DataSource dataSource;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void fixedClock() {
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void grantsOnceAndReturnsStoredSnapshotEvenAfterNewPeriod() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 0);
        String key = UUID.randomUUID().toString();

        var first = post(token(owner), key, "");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        var firstBody = mapper.readTree(first.body());
        assertThat(firstBody.get("claimedAmount").asLong()).isEqualTo(10);
        assertThat(firstBody.get("availableBalance").asLong()).isEqualTo(10);
        assertThat(firstBody.get("lockedBalance").asLong()).isZero();
        assertThat(firstBody.get("claimedForDate").asString()).isEqualTo("2026-11-16");
        assertThat(firstBody.get("alreadyClaimed").asBoolean()).isFalse();
        assertThat(firstBody.get("nextClaimAt").asString()).isEqualTo("2026-11-17T12:34:56Z");
        assertThat(firstBody.get("serverTime").asString()).isEqualTo("2026-11-16T12:34:56Z");
        assertThat(count("daily_reward_claims", owner)).isEqualTo(1);
        assertThat(count("coin_ledger_entries", owner)).isEqualTo(1);
        assertThat(count("daily_reward_requests", owner)).isEqualTo(1);
        assertThat(balance(owner)).isEqualTo(10);

        when(clock.instant()).thenReturn(NOW.plusSeconds(25 * 3600));
        var repeat = post(token(owner), key, "");

        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(repeat.body()).isEqualTo(first.body());
        assertThat(count("daily_reward_claims", owner)).isEqualTo(1);
        assertThat(count("coin_ledger_entries", owner)).isEqualTo(1);
        assertThat(count("daily_reward_requests", owner)).isEqualTo(1);
        assertThat(balance(owner)).isEqualTo(10);
    }

    @Test
    void rejectsNewKeyDuringUnavailablePeriodWithoutGranting() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 10);
        createClaim(owner, LocalDate.parse("2026-11-16"), NOW.minusSeconds(3600));

        var response = post(token(owner), UUID.randomUUID().toString(), "");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = mapper.readTree(response.body());
        assertThat(body.get("claimedAmount").asLong()).isZero();
        assertThat(body.get("availableBalance").asLong()).isEqualTo(10);
        assertThat(body.get("alreadyClaimed").asBoolean()).isTrue();
        assertThat(body.get("claimedForDate").asString()).isEqualTo("2026-11-16");
        assertThat(body.get("nextClaimAt").asString()).isEqualTo("2026-11-17T11:34:56Z");
        assertThat(count("daily_reward_claims", owner)).isEqualTo(1);
        assertThat(count("coin_ledger_entries", owner)).isEqualTo(1);
        assertThat(count("daily_reward_requests", owner)).isEqualTo(1);
        assertThat(balance(owner)).isEqualTo(10);
    }

    @Test
    void grantsNextRewardAfterTwentyFourHours() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 10);
        createClaim(owner, LocalDate.parse("2026-11-15"), NOW.minusSeconds(25 * 3600));

        var response = post(token(owner), UUID.randomUUID().toString(), "");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = mapper.readTree(response.body());
        assertThat(body.get("claimedAmount").asLong()).isEqualTo(10);
        assertThat(body.get("availableBalance").asLong()).isEqualTo(20);
        assertThat(body.get("alreadyClaimed").asBoolean()).isFalse();
        assertThat(body.get("claimedForDate").asString()).isEqualTo("2026-11-16");
        assertThat(count("daily_reward_claims", owner)).isEqualTo(2);
        assertThat(count("coin_ledger_entries", owner)).isEqualTo(2);
        assertThat(balance(owner)).isEqualTo(20);
    }

    @Test
    void rejectsMissingOrMalformedIdempotencyKey() throws Exception {
        assertError(post(token(UUID.randomUUID()), null, ""), 400, "invalid_request");
        assertError(post(token(UUID.randomUUID()), "not-a-uuid", ""), 400, "invalid_request");
        assertError(post(token(UUID.randomUUID()), "{" + UUID.randomUUID() + "}", ""), 400, "invalid_request");
    }

    @Test
    void rejectsQueryString() throws Exception {
        var response = post(token(UUID.randomUUID()), UUID.randomUUID().toString(), "unexpected=true");

        assertError(response, 400, "invalid_request");
    }

    @Test
    void rejectsMissingBearer() throws Exception {
        var response = post(null, UUID.randomUUID().toString(), "");

        assertError(response, 401, "invalid_access_token");
    }

    @Test
    void reportsMissingWalletAsInvariantViolation() throws Exception {
        var response = post(token(UUID.randomUUID()), UUID.randomUUID().toString(), "");

        assertError(response, 500, "wallet_invariant_violation");
    }

    @Test
    void concurrentRequestsWithSameKeyGrantOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 0);
        String key = UUID.randomUUID().toString();

        List<HttpResponse<String>> responses = sendConcurrently(
            () -> post(token(owner), key, ""),
            () -> post(token(owner), key, "")
        );

        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
        assertThat(count("daily_reward_claims", owner)).isEqualTo(1);
        assertThat(count("coin_ledger_entries", owner)).isEqualTo(1);
        assertThat(count("daily_reward_requests", owner)).isEqualTo(1);
        assertThat(balance(owner)).isEqualTo(10);
        assertThat(responses.get(0).body()).isEqualTo(responses.get(1).body());
    }

    @Test
    void concurrentRequestsWithDifferentKeysGrantOnlyOneReward() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 0);

        List<HttpResponse<String>> responses = sendConcurrently(
            () -> post(token(owner), UUID.randomUUID().toString(), ""),
            () -> post(token(owner), UUID.randomUUID().toString(), "")
        );

        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
        assertThat(count("daily_reward_claims", owner)).isEqualTo(1);
        assertThat(count("coin_ledger_entries", owner)).isEqualTo(1);
        assertThat(count("daily_reward_requests", owner)).isEqualTo(2);
        assertThat(balance(owner)).isEqualTo(10);
        List<Boolean> alreadyClaimed = responses.stream()
            .map(response -> mapper.readTree(response.body()).get("alreadyClaimed").asBoolean())
            .toList();
        assertThat(alreadyClaimed).containsExactlyInAnyOrder(false, true);
    }

    @Test
    void returnsServiceUnavailableWithRetryAfterWhenWalletLockIsBusy() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 0);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(() -> holdWalletLock(owner, locked, release));
        holder.start();
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            long started = System.nanoTime();
            var response = post(token(owner), UUID.randomUUID().toString(), "");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertError(response, 503, "coins_unavailable");
            assertThat(response.headers().firstValue("Retry-After")).contains("1");
            assertThat(elapsedMillis).isLessThan(4000);
            assertThat(count("daily_reward_claims", owner)).isZero();
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    private void holdWalletLock(UUID owner, CountDownLatch locked, CountDownLatch release) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_id FROM coin_wallets WHERE user_id = ? FOR UPDATE")) {
                statement.setObject(1, owner);
                statement.executeQuery().close();
            }
            locked.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            connection.rollback();
        } catch (Exception _) {
            locked.countDown();
        }
    }

    private List<HttpResponse<String>> sendConcurrently(Request request1, Request request2) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(request1::send);
            var second = executor.submit(request2::send);
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface Request {
        HttpResponse<String> send() throws Exception;
    }

    private void createWallet(UUID userId, long availableBalance) {
        transactions.executeWithoutResult(_ -> {
            wallets.insertIfAbsent(userId);
            entityManager.createNativeQuery("""
                    UPDATE coin_wallets
                    SET available_balance = :available
                    WHERE user_id = :id
                    """)
                .setParameter("available", availableBalance)
                .setParameter("id", userId)
                .executeUpdate();
        });
    }

    private void createClaim(UUID userId, LocalDate claimDate, Instant claimedAt) {
        UUID claimId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        ledgerEntries.save(new LedgerEntryEntity(ledgerId, userId, claimId, 0,
            LedgerEntryType.DAILY_REWARD, 10, 0, null, null, claimedAt));
        claims.save(new DailyRewardClaimEntity(claimId, userId, claimDate, claimedAt, 10, ledgerId));
    }

    private long count(String table, UUID userId) {
        return transactions.execute(_ -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM " + table + " WHERE user_id = :id")
            .setParameter("id", userId)
            .getSingleResult()).longValue());
    }

    private long balance(UUID userId) {
        return transactions.execute(_ -> ((Number) entityManager.createNativeQuery(
                "SELECT available_balance FROM coin_wallets WHERE user_id = :id")
            .setParameter("id", userId)
            .getSingleResult()).longValue());
    }

    private HttpResponse<String> post(String bearer, String idempotencyKey, String query) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/v1/coins/daily-reward/claim" + (query.isEmpty() ? "" : "?" + query)))
                .header("X-Request-Id", "client-id")
                .POST(HttpRequest.BodyPublishers.noBody());
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token(UUID userId) throws Exception {
        var jwtClaims = new JWTClaimsSet.Builder()
            .subject(userId.toString())
            .claim("sid", UUID.randomUUID().toString())
            .build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), jwtClaims);
        token.sign(new MACSigner(new byte[32]));
        return token.serialize();
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(requestId)).isNotNull();
        var error = mapper.readTree(response.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.get("message")).isNotNull();
        assertThat(error.get("details").isArray()).isTrue();
        assertThat(error.get("details").size()).isZero();
        assertThat(error.get("requestId").asString()).isEqualTo(requestId);
    }
}
