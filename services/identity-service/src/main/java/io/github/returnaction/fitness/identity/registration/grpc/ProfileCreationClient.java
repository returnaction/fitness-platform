package io.github.returnaction.fitness.identity.registration.grpc;

import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.ProfileCreationServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreateProfileIfAbsentRequest;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreationStatus;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Внутренний mTLS-клиент, используемый обработчиком регистрации. */
@Component
public final class ProfileCreationClient {
    private final ManagedChannel channel;

    public ProfileCreationClient(
                                 @Value("${identity.accounts-grpc.host}") String host,
                                 @Value("${identity.accounts-grpc.port}") int port,
                                 @Value("${identity.accounts-grpc.certificate}") String certificate,
                                 @Value("${identity.accounts-grpc.private-key}") String privateKey,
                                 @Value("${identity.accounts-grpc.trust-certificate}") String trustCertificate) throws IOException {
        channel = NettyChannelBuilder.forAddress(host, port)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(new File(trustCertificate))
                        .keyManager(new File(certificate), new File(privateKey))
                        .build())
                .build();
    }

    public void createProfileIfAbsent(UUID requestId, UUID operationId, UUID userId) {
        var response = ProfileCreationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .createProfileIfAbsent(CreateProfileIfAbsentRequest.newBuilder()
                        .setRequestId(requestId.toString())
                        .setRegistrationOperationId(operationId.toString())
                        .setUserId(userId.toString())
                        .build());
        if (!response.getUserId().equals(userId.toString())
                || response.getStatus() != CreationStatus.CREATION_STATUS_READY) {
            throw new IllegalStateException("Invalid Accounts creation response");
        }
    }

    @PreDestroy
    public void close() {
        channel.shutdownNow();
    }
}
