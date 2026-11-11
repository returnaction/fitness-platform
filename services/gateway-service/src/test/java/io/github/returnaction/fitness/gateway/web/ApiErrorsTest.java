package io.github.returnaction.fitness.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ApiErrorsTest {
    @ParameterizedTest
    @CsvSource({
            "400, invalid_request, Check the request format.",
            "401, invalid_access_token, Authentication required.",
            "405, method_not_allowed, HTTP method is not supported.",
            "429, rate_limited, Try again later.",
            "503, identity_unavailable, Identity service is unavailable.",
            "500, internal_error, Unexpected server error.",
            "404, not_found, Request could not be completed.",
            "413, payload_too_large, Request could not be completed.",
            "502, upstream_unavailable, Request could not be completed.",
            "504, upstream_timeout, Request could not be completed."
    })
    void preservesIdentityErrorContractAndGatewayExtensions(int status, String code, String message) throws Exception {
        String requestId = "c54a72ea-6c53-46a3-a3bb-d0a5a0b9c251";
        var response = new MockHttpServletResponse();
        new ApiErrors().write(response, status, code, requestId);
        var mapper = JsonMapper.builder().build();
        var expected = mapper.readTree("""
                {"error":{"code":"%s","message":"%s","details":[],"request_id":"%s"}}
                """.formatted(code, message, requestId));
        assertThat(mapper.readTree(response.getContentAsByteArray())).isEqualTo(expected);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(requestId);
        if (status == 401) {
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        }
    }
}
