package io.github.returnaction.fitness.identity.registration.grpc;

import io.github.returnaction.fitness.contracts.coins.provisioning.v1.CoinsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletRequest;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.ProvisioningStatus;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Внутренний mTLS-клиент, используемый обработчиком регистрации. */
@Component
@ConditionalOnProperty(name = "identity.coins-grpc.enabled", havingValue = "true")
public final class CoinsProvisioningClient {
    private final ManagedChannel channel;

    public CoinsProvisioningClient(
                                   @Value("${identity.coins-grpc.host}") String host,
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

    public void ensureWallet(UUID requestId, UUID operationId, UUID userId) {
        var response = CoinsProvisioningServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .ensureWallet(EnsureWalletRequest.newBuilder()
                        .setRequestId(requestId.toString())
                        .setRegistrationOperationId(operationId.toString())
                        .setUserId(userId.toString())
                        .build());
        if (!response.getUserId().equals(userId.toString())
                || response.getStatus() != ProvisioningStatus.PROVISIONING_STATUS_READY) {
            throw new IllegalStateException("Invalid Coins provisioning response");
        }
    }

    @PreDestroy
    public void close() {
        channel.shutdownNow();
    }
}
