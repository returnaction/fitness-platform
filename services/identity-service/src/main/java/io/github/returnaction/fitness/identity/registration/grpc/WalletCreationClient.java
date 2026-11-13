package io.github.returnaction.fitness.identity.registration.grpc;

import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentRequest;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreationStatus;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.WalletCreationServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Внутренний mTLS-клиент, используемый обработчиком регистрации.
 */
@Component
public final class WalletCreationClient {

    private final ManagedChannel channel;

    public WalletCreationClient(@Value("${identity.coins-grpc.host}") String host,
                                @Value("${identity.coins-grpc.port}") int port,
                                @Value("${identity.coins-grpc.certificate}") String certificate,
                                @Value("${identity.coins-grpc.private-key}") String privateKey,
                                @Value("${identity.coins-grpc.trust-certificate}") String trustCertificate)
        throws IOException {

        channel = NettyChannelBuilder.forAddress(host, port)
            .sslContext(GrpcSslContexts.forClient()
                .trustManager(new File(trustCertificate))
                .keyManager(new File(certificate), new File(privateKey))
                .build())
            .build();
    }

    public void createWalletIfAbsent(UUID requestId, UUID operationId, UUID userId) {
        var response = WalletCreationServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(3, TimeUnit.SECONDS)
            .createWalletIfAbsent(CreateWalletIfAbsentRequest.newBuilder()
                .setRequestId(requestId.toString())
                .setRegistrationOperationId(operationId.toString())
                .setUserId(userId.toString())
                .build());
        if (!response.getUserId().equals(userId.toString())
            || response.getStatus() != CreationStatus.CREATION_STATUS_READY) {
            throw new IllegalStateException("Invalid Coins creation response");
        }
    }

    @PreDestroy
    public void close() {
        channel.shutdownNow();
    }
}
