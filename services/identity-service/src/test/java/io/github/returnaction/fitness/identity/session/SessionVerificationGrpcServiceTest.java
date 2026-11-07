package io.github.returnaction.fitness.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionResponse;
import io.github.returnaction.fitness.contracts.identity.session.v1.SessionStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class SessionVerificationGrpcServiceTest {
    private final SessionVerificationRepository repository = mock(SessionVerificationRepository.class);
    private final SessionVerificationGrpcService service = new SessionVerificationGrpcService(repository);

    @Test
    void returnsStatusAndDatabaseTimestamp() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Instant time = Instant.parse("2026-01-01T12:00:00Z");
        when(repository.check(user, session)).thenReturn(new SessionVerificationRepository.Result(true, time));
        StreamObserver<CheckSessionResponse> observer = observer();
        service.checkSession(request(user.toString(), session.toString()), observer);
        var response = ArgumentCaptor.forClass(CheckSessionResponse.class);
        verify(observer).onNext(response.capture());
        verify(observer).onCompleted();
        assertThat(response.getValue().getStatus()).isEqualTo(SessionStatus.SESSION_STATUS_ACTIVE);
        assertThat(response.getValue().getCheckedAt().getSeconds()).isEqualTo(time.getEpochSecond());
        when(repository.check(user, session)).thenReturn(new SessionVerificationRepository.Result(false, time));
        StreamObserver<CheckSessionResponse> invalid = observer();
        service.checkSession(request(user.toString(), session.toString()), invalid);
        verify(invalid).onNext(response.capture());
        assertThat(response.getValue().getStatus()).isEqualTo(SessionStatus.SESSION_STATUS_INVALID);
    }

    @Test
    void rejectsInvalidIdsAndUnavailableDatabase() {
        StreamObserver<CheckSessionResponse> invalid = observer();
        service.checkSession(request("invalid", UUID.randomUUID().toString()), invalid);
        var error = ArgumentCaptor.forClass(Throwable.class);
        verify(invalid).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(repository);
        when(repository.check(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("private database details"));
        StreamObserver<CheckSessionResponse> failure = observer();
        service.checkSession(
                request(UUID.randomUUID().toString(), UUID.randomUUID().toString()), failure);
        verify(failure).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(Status.fromThrowable(error.getValue()).getDescription()).doesNotContain("private");
        org.mockito.Mockito.doThrow(
                        new org.springframework.transaction.CannotCreateTransactionException("database unavailable"))
                .when(repository)
                .check(any(), any());
        StreamObserver<CheckSessionResponse> transactionFailure = observer();
        service.checkSession(
                request(UUID.randomUUID().toString(), UUID.randomUUID().toString()), transactionFailure);
        verify(transactionFailure).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    private CheckSessionRequest request(String user, String session) {
        return CheckSessionRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setUserId(user)
                .setSessionId(session)
                .build();
    }

    @SuppressWarnings("unchecked")
    private StreamObserver<CheckSessionResponse> observer() {
        return mock(StreamObserver.class);
    }
}
