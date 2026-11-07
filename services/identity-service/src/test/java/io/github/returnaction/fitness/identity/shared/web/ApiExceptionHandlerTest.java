package io.github.returnaction.fitness.identity.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {
    @Test
    void databaseFailureDoesNotExposeExceptionMessage() {
        var request = new MockHttpServletRequest();
        var response = new ApiExceptionHandler()
                .handleDatabaseFailure(
                        new DataAccessResourceFailureException("private@example.com secret-token SELECT password"),
                        request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString())
                .doesNotContain("private@example.com", "secret-token", "SELECT", "password");
    }

    @Test
    void unexpectedErrorDoesNotLogExceptionMessageOrCause() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest();
            String requestId = UUID.randomUUID().toString();
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
            var exception = new IllegalStateException("private@example.com", new RuntimeException("secret-token"));

            var response = new ApiExceptionHandler().handleUnexpected(exception, request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).contains(requestId, "java.lang.IllegalStateException");
            assertThat(event.getFormattedMessage()).doesNotContain("private@example.com", "secret-token");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
