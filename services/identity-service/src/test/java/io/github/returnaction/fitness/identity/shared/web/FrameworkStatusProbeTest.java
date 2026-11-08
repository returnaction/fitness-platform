package io.github.returnaction.fitness.identity.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class FrameworkStatusProbeTest {
    @Test
    void inspectUnsupportedMethod() throws Exception {
        MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.error.code").value("method_not_allowed"))
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    void responseSerializationFailureReturnsSafe500BeforeResponseIsCommitted() throws Exception {
        var response = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(get("/broken-response"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("secret-token", "private@example.com");
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String get() {
            return "ok";
        }

        @GetMapping("/broken-response")
        Object brokenResponse() {
            return new BrokenResponse();
        }
    }

    static class BrokenResponse {
        public String getValue() {
            throw new IllegalStateException("secret-token private@example.com");
        }
    }
}
