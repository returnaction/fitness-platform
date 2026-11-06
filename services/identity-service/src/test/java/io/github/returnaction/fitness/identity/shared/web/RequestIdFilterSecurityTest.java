package io.github.returnaction.fitness.identity.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
            "SERVER_PORT=0",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        })
@AutoConfigureMockMvc
class RequestIdFilterSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void addsRequestIdToRequestRejectedBySecurity() throws Exception {
        mockMvc.perform(get("/not-public"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Request-Id"));
    }
}
