package io.github.returnaction.fitness.gateway.routing;

import java.util.List;

import static io.github.returnaction.fitness.gateway.routing.RouteAccess.AUTHENTICATED;
import static io.github.returnaction.fitness.gateway.routing.RouteAccess.PUBLIC;
import static io.github.returnaction.fitness.gateway.routing.UpstreamId.ACCOUNT;
import static io.github.returnaction.fitness.gateway.routing.UpstreamId.CHALLENGE;
import static io.github.returnaction.fitness.gateway.routing.UpstreamId.COINS;
import static io.github.returnaction.fitness.gateway.routing.UpstreamId.IDENTITY;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

/**
 * Каталог разрешённых операций Gateway.
 *
 * <p>Spec 017 T003.3: добавлен защищённый маршрут
 * {@code GET /api/v1/coins/me → COINS, AUTHENTICATED}.</p>
 *
 * <p>Spec 020 T003.3: добавлены восемь защищённых маршрутов Challenge.
 * Внутренний gRPC результата в каталог не входит.</p>
 */
public final class GatewayRouteCatalog {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String CHALLENGE_BASE_PATH = "/api/v1/challenges/";
    private static final String CHALLENGE_OPERATIONS_PATH = "/api/v1/challenge-operations/";
    private static final String CHALLENGE_ID_SEGMENT = "{challengeId:" + UUID_PATTERN + "}";
    private static final String OPERATION_ID_SEGMENT = "{operationId:" + UUID_PATTERN + "}";
    private static final String CHALLENGE_PATH = CHALLENGE_BASE_PATH + CHALLENGE_ID_SEGMENT;

    private static final List<GatewayRoute> ROUTES = List.of(
            new GatewayRoute(POST, "/api/v1/auth/register", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/login", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/refresh", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/logout", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/logout-all", IDENTITY, AUTHENTICATED),

            new GatewayRoute(GET, "/api/v1/accounts/me", ACCOUNT, AUTHENTICATED),
            new GatewayRoute(PUT, "/api/v1/accounts/me", ACCOUNT, AUTHENTICATED),

            new GatewayRoute(GET, "/api/v1/coins/me", COINS, AUTHENTICATED),

            new GatewayRoute(POST, CHALLENGE_BASE_PATH + "drafts", CHALLENGE, AUTHENTICATED),
            new GatewayRoute(GET, CHALLENGE_PATH, CHALLENGE, AUTHENTICATED),
            new GatewayRoute(PATCH, CHALLENGE_PATH + "/draft", CHALLENGE, AUTHENTICATED),
            new GatewayRoute(POST, CHALLENGE_PATH + "/publish", CHALLENGE, AUTHENTICATED),
            new GatewayRoute(POST, CHALLENGE_PATH + "/joins", CHALLENGE, AUTHENTICATED),
            new GatewayRoute(POST, CHALLENGE_PATH + "/cancel", CHALLENGE, AUTHENTICATED),
            new GatewayRoute(GET, CHALLENGE_OPERATIONS_PATH + OPERATION_ID_SEGMENT, CHALLENGE, AUTHENTICATED),
            new GatewayRoute(GET, CHALLENGE_PATH + "/result", CHALLENGE, AUTHENTICATED));

    private GatewayRouteCatalog() {
    }

    public static List<GatewayRoute> routes() {
        return ROUTES;
    }
}
