package io.github.returnaction.fitness.coins.wallet.grpc;

import io.github.returnaction.fitness.coins.wallet.WalletProvisioningService;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.CoinsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletRequest;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletResponse;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.ProvisioningStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Адаптер контракта. Не регистрировать в сетевом сервере без mTLS и
 * авторизации.
 */
@Component
public class CoinsProvisioningGrpcService extends CoinsProvisioningServiceGrpc.CoinsProvisioningServiceImplBase {
    private final WalletProvisioningService provisioning;

    public CoinsProvisioningGrpcService(WalletProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public void ensureWallet(EnsureWalletRequest request, StreamObserver<EnsureWalletResponse> observer) {
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
            provisioning.ensureWallet(userId);
            observer.onNext(EnsureWalletResponse.newBuilder()
                    .setUserId(userId.toString())
                    .setStatus(ProvisioningStatus.PROVISIONING_STATUS_READY)
                    .build());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL
                    .withDescription("Wallet provisioning failed")
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
