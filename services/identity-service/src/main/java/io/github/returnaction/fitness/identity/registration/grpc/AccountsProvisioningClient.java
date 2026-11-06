package io.github.returnaction.fitness.identity.registration.grpc;

import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
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

/** Внутренний mTLS-клиент; worker подключит его в следующем инкременте. */
@Component
@ConditionalOnProperty(name = "identity.accounts-grpc.enabled", havingValue = "true")
public final class AccountsProvisioningClient {
    private final ManagedChannel channel;

    public AccountsProvisioningClient(
            @Value("${identity.accounts-grpc.host}") String host,
            @Value("${identity.accounts-grpc.port}") int port,
            @Value("${identity.accounts-grpc.certificate}") String certificate,
            @Value("${identity.accounts-grpc.private-key}") String privateKey,
            @Value("${identity.accounts-grpc.trust-certificate}") String trustCertificate)
            throws IOException {
        channel = NettyChannelBuilder.forAddress(host, port)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(new File(trustCertificate))
                        .keyManager(new File(certificate), new File(privateKey))
                        .build())
                .build();
    }

    public void ensureProfile(UUID requestId, UUID operationId, UUID userId) {
        var response = AccountsProvisioningServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .ensureProfile(EnsureProfileRequest.newBuilder()
                        .setRequestId(requestId.toString())
                        .setRegistrationOperationId(operationId.toString())
                        .setUserId(userId.toString())
                        .build());
        if (!response.getUserId().equals(userId.toString())
                || response.getStatus() != ProvisioningStatus.PROVISIONING_STATUS_READY) {
            throw new IllegalStateException("Invalid Accounts provisioning response");
        }
    }

    @PreDestroy
    public void close() {
        channel.shutdownNow();
    }
}
