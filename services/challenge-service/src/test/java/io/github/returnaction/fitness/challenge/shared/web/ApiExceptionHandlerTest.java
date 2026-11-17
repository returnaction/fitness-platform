package io.github.returnaction.fitness.challenge.shared.web;

import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.ACTIVE;
import static io.github.returnaction.fitness.challenge.domain.ChallengeStatus.DRAFT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.challenge.domain.InvalidStateTransitionException;
import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.challenge.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    private static final String REQUEST_ID = "da6add51-47f8-4603-a9ce-17e440a8da64";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void requestId() {
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
    }

    @Test
    void mapsInvalidRequestTo400() {
        var response = handler.handleInvalidRequest(request);

        assertError(response, HttpStatus.BAD_REQUEST, "invalid_request");
    }

    @Test
    void mapsInvalidTransitionTo422WithStatusDetail() {
        var response = handler.handleInvalidTransition(new InvalidStateTransitionException(DRAFT, ACTIVE), request);

        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, "validation_error");
        assertThat(response.getBody().error().details())
                .containsExactly(new ApiErrorResponse.ErrorDetail("status", "invalid_transition"));
    }

    @Test
    void mapsNotFoundWithSpecCode() {
        var response = handler.handleNotFound(
                new ResourceNotFoundException("challenge_not_found", "Challenge not found."), request);

        assertError(response, HttpStatus.NOT_FOUND, "challenge_not_found");
        assertThat(response.getBody().error().message()).isEqualTo("Challenge not found.");
    }

    @Test
    void mapsBusinessConflictWithSpecCode() {
        var response = handler.handleBusinessConflict(
                new BusinessConflictException("version_conflict", "Version conflict."), request);

        assertError(response, HttpStatus.CONFLICT, "version_conflict");
    }

    @Test
    void mapsAuthenticationTo401() {
        var response = handler.handleAuthentication(request);

        assertError(response, HttpStatus.UNAUTHORIZED, "unauthenticated");
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void mapsAccessDeniedTo403() {
        var response = handler.handleAccessDenied(request);

        assertError(response, HttpStatus.FORBIDDEN, "access_denied");
    }

    @Test
    void mapsStorageFailureTo503WithoutInternalDetails() {
        var response = handler.handleUnavailable(
                new DataAccessResourceFailureException("private database details"), request);

        assertError(response, HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable");
        assertThat(response.getBody().error().message()).doesNotContain("private database details");
    }

    @Test
    void mapsUnexpectedTo500WithoutInternalDetails() {
        var response = handler.handleUnexpected(new IllegalStateException("private diagnostics"), request);

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        assertThat(response.getBody().error().message()).doesNotContain("private diagnostics");
    }

    private void assertError(ResponseEntity<ApiErrorResponse> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(code);
        assertThat(response.getBody().error().requestId()).isEqualTo(REQUEST_ID);
    }
}
