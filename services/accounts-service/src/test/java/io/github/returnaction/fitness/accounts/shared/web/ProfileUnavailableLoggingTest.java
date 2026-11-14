package io.github.returnaction.fitness.accounts.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class ProfileUnavailableLoggingTest {

    @Test
    void logsMissingProfileReasonAndSafeDatabaseType(CapturedOutput output) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
        String id = "2c160d4b-0f61-4702-99e1-99713d0c9326";
        for (String path : new String[]{"/missing", "/database"}) {
            mvc.perform(get(path).header("X-Request-Id", id))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("profile_unavailable"))
                    .andExpect(jsonPath("$.error.requestId").value(id))
                    .andExpect(jsonPath("$.error.details").isEmpty())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("secret-token", "SELECT", "DataAccessResourceFailureException"));
        }
        assertThat(output.getAll())
                .contains("request_id=" + id, "reason=profile_missing", "reason=DataAccessResourceFailureException")
                .doesNotContain("secret-token", "SELECT private_data");
    }

    @RestController
    @Profile("profile-logging-test")
    static class FailureController {

        @GetMapping("/missing")
        void missing() {
            throw new ProfileUnavailableException();
        }
        @GetMapping("/database")
        void database() {
            throw new ProfileUnavailableException(
                    new DataAccessResourceFailureException("SELECT private_data; secret-token"));
        }
    }
}
