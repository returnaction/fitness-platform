package io.github.returnaction.fitness.identity.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class ApiExceptionHandlerTest {

    @Test
    void authenticationFailuresKeepTheirStatusMessageAndHeaders() {
        record Case(AuthenticationFailureException.Reason reason, int status, String code, String message) {
        }
        var cases = java.util.List.of(
                new Case(AuthenticationFailureException.Reason.INVALID_CREDENTIALS, 401,
                        "invalid_credentials", "Invalid email or password."),
                new Case(AuthenticationFailureException.Reason.ACCOUNT_UNAVAILABLE, 403,
                        "account_unavailable", "Account is unavailable."),
                new Case(AuthenticationFailureException.Reason.REGISTRATION_PENDING, 503,
                        "registration_pending", "Registration is being prepared. Try again later."),
                new Case(AuthenticationFailureException.Reason.INVALID_REFRESH, 401,
                        "invalid_refresh_token", "Request could not be completed."),
                new Case(AuthenticationFailureException.Reason.REFRESH_ACCOUNT_UNAVAILABLE, 403,
                        "account_unavailable", "Request could not be completed."));
        var handler = new ApiExceptionHandler();
        var request = new MockHttpServletRequest();
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
        for (var item : cases) {
            var response = handler.handleAuthenticationFailure(new AuthenticationFailureException(item.reason()),
                    request);
            assertThat(response.getStatusCode().value()).isEqualTo(item.status());
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().error().code()).isEqualTo(item.code());
            assertThat(response.getBody().error().message()).isEqualTo(item.message());
            assertThat(response.getBody().error().requestId()).isEqualTo(requestId);
            assertThat(response.getBody().error().details()).isEmpty();
        }
    }

    @Test
    void methodNotAllowedKeepsRequestIdAndSupportedMethods() {
        var request = new MockHttpServletRequest();
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);

        var response = new ApiExceptionHandler().handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET")), request);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getAllow()).containsExactly(org.springframework.http.HttpMethod.GET);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("method_not_allowed");
        assertThat(response.getBody().error().requestId()).isEqualTo(requestId);
    }

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
