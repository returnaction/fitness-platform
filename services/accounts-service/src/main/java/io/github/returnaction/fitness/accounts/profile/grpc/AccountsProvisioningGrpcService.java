package io.github.returnaction.fitness.accounts.profile.grpc;

import io.github.returnaction.fitness.accounts.profile.service.ProfileProvisioningService;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileResponse;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Адаптер контракта. Не регистрировать в сетевом сервере без mTLS и
 * авторизации.
 */
@Component
public class AccountsProvisioningGrpcService
        extends
            AccountsProvisioningServiceGrpc.AccountsProvisioningServiceImplBase {
    private final ProfileProvisioningService provisioning;

    public AccountsProvisioningGrpcService(ProfileProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public void ensureProfile(EnsureProfileRequest request, StreamObserver<EnsureProfileResponse> observer) {
        UUID userId;
        try {
            canonicalUuid(request.getRequestId());
            canonicalUuid(request.getRegistrationOperationId());
            userId = canonicalUuid(request.getUserId());
        } catch (IllegalArgumentException exception) {
            observer.onError(
                    Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
            return;
        }
        try {
            provisioning.ensureProfile(userId);
            observer.onNext(EnsureProfileResponse.newBuilder()
                    .setUserId(userId.toString())
                    .setStatus(ProvisioningStatus.PROVISIONING_STATUS_READY)
                    .build());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL
                    .withDescription("Profile provisioning failed")
                    .asRuntimeException());
        }
    }

    private UUID canonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("Noncanonical UUID");
        }
        return uuid;
    }
}
