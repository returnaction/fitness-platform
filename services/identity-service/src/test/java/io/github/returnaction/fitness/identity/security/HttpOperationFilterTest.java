package io.github.returnaction.fitness.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class HttpOperationFilterTest {

    private final HttpOperationFilter filter = new HttpOperationFilter(
            new MockEnvironment().withProperty("local.server.port", "8080")
                    .withProperty("local.management.port", "9080"),
            JsonMapper.builder().build());

    @Test
    void permitsHttpOperationsWithoutClientCertificates() throws Exception {
        for (String operation : List.of("register", "login", "refresh", "logout", "logout-all")) {
            assertAllowed(request("POST", "/api/v1/auth/" + operation));
        }
        assertAllowed(request("GET", "/.well-known/jwks.json"));
    }

    @Test
    void deniesRecoveryUnknownOperationsAndManagementOnApiPort() throws Exception {
        for (String path : List.of("/api/v1/admin/identity/registration-operations/123", "/actuator/health",
                "/actuator/prometheus", "/unknown")) {
            assertDenied(request("GET", path));
        }
        assertDenied(request("GET", "/api/v1/auth/login"));
        assertDenied(request("POST", "/.well-known/jwks.json"));
    }

    @Test
    void managementPortNeverExposesApplicationOperations() throws Exception {
        var health = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        health.setLocalPort(9080);
        assertAllowed(health);
        var login = request("POST", "/api/v1/auth/login");
        login.setLocalPort(9080);
        assertDenied(login);
        health.setMethod("POST");
        assertDenied(health);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setLocalPort(8080);
        return request;
    }

    private void assertAllowed(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void assertDenied(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("forbidden");
    }
}
