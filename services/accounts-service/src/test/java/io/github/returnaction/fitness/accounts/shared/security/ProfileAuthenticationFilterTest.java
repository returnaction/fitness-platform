package io.github.returnaction.fitness.accounts.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ProfileAuthenticationFilterTest {

    static final String ID = "2c160d4b-0f61-4702-99e1-99713d0c9326";
    private ProfileAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        var errors = mock(SecurityExceptionHandler.class);
        doAnswer(invocation -> {
            MockHttpServletResponse response = invocation.getArgument(1);
            response.setStatus(401);
            return null;
        }).when(errors).commence(any(), any(), any());
        filter = new ProfileAuthenticationFilter(errors);
    }

    @AfterEach
    void clearContext() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        SecurityContextHolder.clearContext();
    }

    // Parsing-only fixture: deliberately not signed, since signature verification
    // belongs to Gateway.
    static String token(String claims) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".AQ";
    }

    private MockHttpServletRequest request(String claims) {
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts/me");
        request.addHeader("Authorization", "Bearer " + token(claims));
        return request;
    }

    @Test
    void mapsOnlyKnownRolesAndUsesUuidPrincipalWithoutCredentials() throws Exception {
        var request = request(
                "{\"sub\":\"" + ID + "\",\"roles\":[\"USER\",\"MODERATOR\",\"ADMIN\",\"SUPER_ADMIN\",\"OTHER\"]}");
        var reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getPrincipal()).isEqualTo(UUID.fromString(ID));
            assertThat(auth.getCredentials()).isNull();
            assertThat(auth.getAuthorities()).extracting("authority")
                    .containsExactly("ROLE_USER", "ROLE_MODERATOR", "ROLE_ADMIN", "ROLE_SUPER_ADMIN");
            reached.set(true);
        });
        assertThat(reached).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"roles\":[]", ",\"roles\":[\"OTHER\"]"})
    void absentEmptyOrUnknownRolesDoNotGrantUserRole(String roles) throws Exception {
        filter.doFilter(request("{\"sub\":\"" + ID + "\"" + roles + "}"), new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getAuthorities()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"sub\":null}", "{\"sub\":123}", "{\"sub\":\"wrong\"}", "{\"sub\":\"1-1-1-1-1\"}",
            "{\"sub\":\"2c160d4b-0f61-4702-99e1-99713d0c9326\",\"roles\":null}",
            "{\"sub\":\"2c160d4b-0f61-4702-99e1-99713d0c9326\",\"roles\":\"USER\"}",
            "{\"sub\":\"2c160d4b-0f61-4702-99e1-99713d0c9326\",\"roles\":[123]}",
            "{\"sub\":\"2c160d4b-0f61-4702-99e1-99713d0c9326\",\"roles\":[null]}",
            "{\"sub\":\"2c160d4b-0f61-4702-99e1-99713d0c9326\",\"roles\":{}}"})
    void rejectsMalformedUsedClaims(String claims) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(claims), response, (req, res) -> {
            throw new AssertionError("Invalid context must not reach controller");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic abc", "Bearer ", "Bearer broken", "Bearer a.b.c"})
    void rejectsMissingOrMalformedBearer(String authorization) throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/v1/accounts/me");
        if (!authorization.isEmpty())
            request.addHeader("Authorization", authorization);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("Must reject");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void onlyExactGetAndPutPathsAreFilteredIncludingContextPath() throws Exception {
        for (String path : List.of("/api/v1/accounts/me/", "/api/v1/accounts/me/other", "/actuator/health")) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", path))).isTrue();
        }
        for (String method : List.of("POST", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest(method, "/api/v1/accounts/me"))).isTrue();
        }
        for (String method : List.of("GET", "PUT")) {
            var request = new MockHttpServletRequest(method, "/accounts/api/v1/accounts/me");
            request.setContextPath("/accounts");
            assertThat(filter.shouldNotFilter(request)).isFalse();
        }
    }

    @Test
    void downstreamFailurePropagatesAndContextIsCleared() {
        assertThatThrownBy(() -> filter.doFilter(request("{\"sub\":\"" + ID + "\"}"), new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IllegalStateException("controller failure");
                })).isInstanceOf(IllegalStateException.class).hasMessage("controller failure");
    }

    @Test
    void parallelRequestsHaveIsolatedContextsAndCleanTheirThreads() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(ID, "f4740051-e998-494a-bc78-a8cbb32d08e9").stream().map(id -> executor.submit(() -> {
                filter.doFilter(request("{\"sub\":\"" + id + "\"}"), new MockHttpServletResponse(), (req, res) -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                            .isEqualTo(UUID.fromString(id));
                });
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                SecurityContextHolder.clearContext();
                return true;
            })).toList();
            for (var future : futures)
                assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
