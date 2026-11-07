package io.github.returnaction.fitness.identity.session;

import com.google.protobuf.Timestamp;
import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionResponse;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import io.github.returnaction.fitness.contracts.identity.session.v1.SessionStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** gRPC адаптер; сетевой доступ разрешён только через mTLS сервер. */
@Component
public class SessionVerificationGrpcService extends IdentitySessionServiceGrpc.IdentitySessionServiceImplBase {
    private final SessionVerificationRepository repository;

    public SessionVerificationGrpcService(SessionVerificationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void checkSession(CheckSessionRequest request, StreamObserver<CheckSessionResponse> observer) {
        UUID user;
        UUID session;
        try {
            canonicalUuid(request.getRequestId());
            user = canonicalUuid(request.getUserId());
            session = canonicalUuid(request.getSessionId());
        } catch (IllegalArgumentException exception) {
            observer.onError(
                    Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
            return;
        }
        try {
            var result = repository.check(user, session);
            var time = result.checkedAt();
            observer.onNext(CheckSessionResponse.newBuilder()
                    .setStatus(
                            result.active()
                                    ? SessionStatus.SESSION_STATUS_ACTIVE
                                    : SessionStatus.SESSION_STATUS_INVALID)
                    .setCheckedAt(Timestamp.newBuilder()
                            .setSeconds(time.getEpochSecond())
                            .setNanos(time.getNano())
                            .build())
                    .build());
            observer.onCompleted();
        } catch (DataAccessException | TransactionException exception) {
            observer.onError(Status.UNAVAILABLE
                    .withDescription("Session verification unavailable")
                    .asRuntimeException());
        }
    }

    private UUID canonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("Invalid UUID");
        return uuid;
    }
}
