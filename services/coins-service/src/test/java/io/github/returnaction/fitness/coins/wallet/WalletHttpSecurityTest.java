package io.github.returnaction.fitness.coins.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import io.github.returnaction.fitness.coins.wallet.service.WalletQueryService;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Проверяет HTTP и security-поведение {@code GET /api/v1/coins/me}.
 *
 * <p>Spec 017 T004.11: покрыты успешный ответ, отсутствие Bearer, невалидный
 * {@code sub}, отсутствующий кошелёк, временная ошибка БД, единый формат
 * ошибок и {@code Cache-Control: no-store}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WalletHttpSecurityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @MockitoBean
    private SecureGrpcServer managedGrpcServer;

    @MockitoSpyBean
    private WalletQueryService walletQueryService;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private EntityManager entityManager;

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

    @Test
    void returnsOwnBalancesWithoutCaching() throws Exception {
        UUID owner = UUID.randomUUID();
        createWallet(owner, 10, 20);

        var response = get(token(owner), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow())).isNotNull();
        var body = mapper.readTree(response.body());
        assertThat(body.get("availableBalance").asLong()).isEqualTo(10);
        assertThat(body.get("lockedBalance").asLong()).isEqualTo(20);
    }

    @Test
    void rejectsMissingBearer() throws Exception {
        var response = get(null, "");

        assertError(response, 401, "invalid_access_token");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void rejectsInvalidSubject() throws Exception {
        var response = get(token("not-a-uuid"), "");

        assertError(response, 401, "invalid_access_token");
    }

    @Test
    void reportsMissingWalletAsInvariantViolation() throws Exception {
        var response = get(token(UUID.randomUUID()), "");

        assertError(response, 500, "wallet_invariant_violation");
    }

    @Test
    void reportsTemporaryDatabaseFailureAsUnavailable() throws Exception {
        UUID userId = UUID.randomUUID();
        doThrow(new CoinsUnavailableException(new DataAccessResourceFailureException("private diagnostics")))
                .when(walletQueryService)
                .getOwnBalance(userId);

        var response = get(token(userId), "");

        assertError(response, 503, "coins_unavailable");
        assertThat(response.body()).doesNotContain("private diagnostics");
    }

    @Test
    void rejectsQueryString() throws Exception {
        var response = get(token(UUID.randomUUID()), "unexpected=true");

        assertError(response, 400, "invalid_request");
    }

    private void createWallet(UUID userId, long availableBalance, long lockedBalance) {
        transactions.executeWithoutResult(status -> {
            wallets.insertIfAbsent(userId);
            entityManager.createNativeQuery("""
                            UPDATE coin_wallets
                            SET available_balance = :available, locked_balance = :locked
                            WHERE user_id = :id
                            """)
                    .setParameter("available", availableBalance)
                    .setParameter("locked", lockedBalance)
                    .setParameter("id", userId)
                    .executeUpdate();
        });
    }

    private HttpResponse<String> get(String bearer, String query) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/v1/coins/me" + (query.isEmpty() ? "" : "?" + query)))
                    .header("X-Request-Id", "client-id")
                    .GET();
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token(UUID userId) throws Exception {
        return token(userId.toString());
    }

    private String token(String subject) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("sid", UUID.randomUUID().toString())
                .build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
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
