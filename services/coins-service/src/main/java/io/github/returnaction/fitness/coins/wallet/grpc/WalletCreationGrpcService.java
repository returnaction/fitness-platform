package io.github.returnaction.fitness.coins.wallet.grpc;

import io.github.returnaction.fitness.coins.wallet.service.WalletCreationService;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.WalletCreationServiceGrpc;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentRequest;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentResponse;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreationStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Адаптер контракта. Не регистрировать в сетевом сервере без mTLS и
 * авторизации.
 */
@Component
@RequiredArgsConstructor
public class WalletCreationGrpcService extends WalletCreationServiceGrpc.WalletCreationServiceImplBase {

    private final WalletCreationService walletCreationService;

    @Override
    public void createWalletIfAbsent(CreateWalletIfAbsentRequest request,
        StreamObserver<CreateWalletIfAbsentResponse> observer) {
        UUID userId;
        try {
            canonicalUuid(request.getRequestId());
            canonicalUuid(request.getRegistrationOperationId());
            userId = canonicalUuid(request.getUserId());
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
            return;
        }
        try {
            walletCreationService.createWalletIfAbsent(userId);
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL.withDescription("Wallet creation failed").asRuntimeException());
            return;
        }
        observer.onNext(CreateWalletIfAbsentResponse.newBuilder()
                .setUserId(userId.toString())
                .setStatus(CreationStatus.CREATION_STATUS_READY)
                .build());
        observer.onCompleted();
    }

    private UUID canonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("Noncanonical UUID");
        }
        return uuid;
    }
}
