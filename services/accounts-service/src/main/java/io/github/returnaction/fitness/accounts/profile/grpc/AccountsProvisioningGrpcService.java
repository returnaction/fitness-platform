package io.github.returnaction.fitness.accounts.profile.grpc;

import io.github.returnaction.fitness.accounts.profile.exception.ProfileCreationExhaustedException;
import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileResponse;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * gRPC-адаптер Accounts v1; mTLS и авторизацию обеспечивает
 * {@link SecureGrpcServer}.
 */
@Component
@RequiredArgsConstructor
public final class AccountsProvisioningGrpcService
        extends
            AccountsProvisioningServiceGrpc.AccountsProvisioningServiceImplBase {

    private final ProfileCreationService profileCreationService;

    @Override
    public void ensureProfile(EnsureProfileRequest request, StreamObserver<EnsureProfileResponse> observer) {

        UUID userId;

        try {
            userId = userIdFrom(request);
        } catch (IllegalArgumentException exception) {
            sendError(observer, Status.INVALID_ARGUMENT, "Invalid UUID");
            return;
        }

        try {
            profileCreationService.createIfAbsent(userId);
        } catch (ProfileCreationExhaustedException exception) {
            sendError(observer, Status.ABORTED, "Profile creation temporarily unavailable");
            return;
        } catch (RuntimeException exception) {
            sendError(observer, Status.INTERNAL, "Profile provisioning failed");
            return;
        }

        sendReady(observer, userId);
    }

    private UUID userIdFrom(EnsureProfileRequest request) {
        parseCanonicalUuid(request.getRequestId());
        parseCanonicalUuid(request.getRegistrationOperationId());
        return parseCanonicalUuid(request.getUserId());
    }

    private UUID parseCanonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("Noncanonical UUID");
        }
        return uuid;
    }

    private void sendReady(StreamObserver<EnsureProfileResponse> observer, UUID userId) {
        observer.onNext(EnsureProfileResponse.newBuilder()
                .setUserId(userId.toString())
                .setStatus(ProvisioningStatus.PROVISIONING_STATUS_READY)
                .build());
        observer.onCompleted();
    }

    private void sendError(StreamObserver<EnsureProfileResponse> observer, Status status, String message) {
        observer.onError(status.withDescription(message).asRuntimeException());
    }
}
