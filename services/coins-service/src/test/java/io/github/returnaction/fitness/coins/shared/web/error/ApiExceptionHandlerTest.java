package io.github.returnaction.fitness.coins.shared.web.error;

import io.github.returnaction.fitness.coins.shared.web.requestid.RequestIdFilter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void logsUnexpectedErrorLocationWithoutPrivateMessages() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "test-request-id");
        var exception = new IllegalStateException("private outer message",
            new IllegalArgumentException("private cause message"));
        exception.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.RewardService", "claim", "RewardService.java", 42)
        });
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = handler.handleUnexpected(exception, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody().error().code()).isEqualTo("internal_error");
            assertThat(response.getBody().error().requestId()).isEqualTo("test-request-id");
            assertThat(response.getBody().error().message()).isEqualTo("Unexpected server error.");
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage())
                .contains("test-request-id", "java.lang.IllegalStateException",
                    "java.lang.IllegalArgumentException", "RewardService.java:42")
                .doesNotContain("private outer message", "private cause message");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void returnsRetryableResponseWhenTransactionCannotStart() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "test-request-id");

        var response = handler.handleCannotCreateTransaction(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().error().code()).isEqualTo("coins_unavailable");
        assertThat(response.getBody().error().message()).isEqualTo("Coins service is temporarily unavailable.");
    }
}
