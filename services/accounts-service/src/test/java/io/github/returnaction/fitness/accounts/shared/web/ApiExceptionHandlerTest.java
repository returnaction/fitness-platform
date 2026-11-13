package io.github.returnaction.fitness.accounts.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    private static final String REQUEST_ID = "2c160d4b-0f61-4702-99e1-99713d0c9326";
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void malformedAndEmptyBodiesReturnSafe400WithOriginalRequestId() throws Exception {
        for (String body : List.of("", "{broken-private-input")) {
            mvc.perform(post("/test/body").header("X-Request-Id", REQUEST_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("X-Request-Id", REQUEST_ID))
                    .andExpect(jsonPath("$.error.requestId").value(REQUEST_ID))
                    .andExpect(jsonPath("$.error.code").value("invalid_request"))
                    .andExpect(jsonPath("$.error.details").isEmpty())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("broken-private-input", "HttpMessageNotReadableException"));
        }
    }

    @Test
    void wrongArgumentTypeReturns400() throws Exception {
        mvc.perform(get("/test/number").param("value", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("private"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"))
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    void validationReturns422WithStableCodesAndNoRejectedValues() throws Exception {
        mvc.perform(get("/test/validation").header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.details[0].field").value("nickname"))
                .andExpect(jsonPath("$.error.details[0].code").value("required"))
                .andExpect(jsonPath("$.error.details[1].code").value("invalid_length"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("private-value", "private-message", "rejectedValue"));
    }

    @Test
    void unexpectedFailureReturnsSafe500AndSameGeneratedRequestId() throws Exception {
        var result = mvc.perform(get("/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(resultValue -> assertThat(resultValue.getResponse().getContentAsString())
                        .doesNotContain("secret-token", "SELECT", "IllegalStateException"))
                .andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).isNotBlank();
        jsonPath("$.error.requestId").value(id).match(result);
    }

    @Test
    void standardMethodAndAcceptErrorsDoNotBecome500() throws Exception {
        mvc.perform(get("/test/body"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.error.code").value("method_not_allowed"));
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.IMAGE_PNG)
                .content("{\"name\":\"hello\"}")).andExpect(status().isNotAcceptable());
    }

    @RestController
    static class TestController {

        @PostMapping(value = "/test/body", consumes = "application/json", produces = "application/json")
        Payload body(@RequestBody Payload payload) {
            return payload;
        }

        @GetMapping("/test/number")
        int number(@RequestParam("value") int value) {
            return value;
        }

        @GetMapping("/test/validation")
        void validation() throws Exception {
            var binding = new BeanPropertyBindingResult(new Payload("private-value"), "payload");
            binding.addError(new FieldError("payload", "nickname", "private-value", false, new String[]{"NotNull"},
                    null, "private-message"));
            binding.addError(new FieldError("payload", "firstName", "private-value", false, new String[]{"Size"}, null,
                    "private-message"));
            var method = TestController.class.getDeclaredMethod("body", Payload.class);
            throw new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);
        }

        @GetMapping("/test/failure")
        void failure() {
            throw new IllegalStateException("SELECT private_data; secret-token");
        }
    }

    record Payload(String name) {
    }
}
