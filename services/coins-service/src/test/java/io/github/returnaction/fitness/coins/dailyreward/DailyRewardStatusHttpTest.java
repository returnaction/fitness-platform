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
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class DailyRewardStatusHttpTest {

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
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

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
    void returnsAvailableRewardAndBalanceWithoutCaching() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 5);

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var response = get(token(owner), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var body = mapper.readTree(response.body());
        assertThat(body.get("available").asBoolean()).isTrue();
        assertThat(body.get("amount").asLong()).isEqualTo(10);
        assertThat(body.get("nextClaimAt").isNull()).isTrue();
        assertThat(body.get("serverTime").asString()).isEqualTo("2026-11-16T12:34:56Z");
        assertThat(body.get("availableBalance").asLong()).isEqualTo(5);
        assertThat(body.get("lockedBalance").asLong()).isZero();
    }

    @Test
    void returnsNextClaimTimeFromLatestClaimAndBothBalancesInOneQuery() throws Exception {
        UUID owner = UUID.randomUUID();
        Instant claimedAt = NOW.minusSeconds(3600);
        createWallet(owner, 10);
        createClaim(owner, claimedAt);
        createClaim(owner, claimedAt.minusSeconds(86400));
        transactions.executeWithoutResult(_ -> entityManager.createNativeQuery(
                "UPDATE coin_wallets SET locked_balance = 7 WHERE user_id = :id")
            .setParameter("id", owner).executeUpdate());
        UUID otherUser = UUID.randomUUID();
        createWallet(otherUser, 100);
        createClaim(otherUser, NOW);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var response = get(token(owner), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        var body = mapper.readTree(response.body());
        assertThat(body.get("available").asBoolean()).isFalse();
        assertThat(body.get("amount").asLong()).isEqualTo(10);
        assertThat(body.get("nextClaimAt").asString()).isEqualTo("2026-11-17T11:34:56Z");
        assertThat(body.get("availableBalance").asLong()).isEqualTo(10);
        assertThat(body.get("lockedBalance").asLong()).isEqualTo(7);
    }

    @Test
    void makesRewardAvailableExactlyAfter24Hours() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 10);
        createClaim(owner, NOW.minusSeconds(86400));

        var response = get(token(owner), "");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = mapper.readTree(response.body());
        assertThat(body.get("available").asBoolean()).isTrue();
        assertThat(body.get("nextClaimAt").isNull()).isTrue();
        assertThat(body.get("availableBalance").asLong()).isEqualTo(10);
    }

    @Test
    void rejectsMissingBearer() throws Exception {
        var response = get(null, "");

        assertError(response, 401, "invalid_access_token");
    }

    @Test
    void rejectsQueryString() throws Exception {
        var response = get(token(UUID.randomUUID()), "unexpected=true");

        assertError(response, 400, "invalid_request");
    }

    @Test
    void reportsMissingWalletAsInvariantViolation() throws Exception {
        var response = get(token(UUID.randomUUID()), "");

        assertError(response, 500, "wallet_invariant_violation");
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

    private void createClaim(UUID userId, Instant claimedAt) {
        UUID claimId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        ledgerEntries.save(new LedgerEntryEntity(ledgerId, userId, claimId, 0,
            LedgerEntryType.DAILY_REWARD_CREDIT, 10, 0, null, null, claimedAt));
        claims.save(new DailyRewardClaimEntity(claimId, userId, claimedAt, 10, ledgerId));
    }

    private HttpResponse<String> get(String bearer, String query) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/v1/coins/daily-reward/status" + (query.isEmpty() ? "" : "?" + query)))
                .header("X-Request-Id", "client-id")
                .GET();
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
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
