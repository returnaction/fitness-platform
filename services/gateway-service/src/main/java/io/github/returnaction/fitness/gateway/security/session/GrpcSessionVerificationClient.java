package io.github.returnaction.fitness.gateway.security.session;

import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import io.github.returnaction.fitness.gateway.configuration.properties.SessionVerificationProperties;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadline;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;

@Component
public final class GrpcSessionVerificationClient implements SessionVerificationClient {

    private final ManagedChannel channel;
    private final IdentitySessionServiceGrpc.IdentitySessionServiceBlockingStub stub;
    private final SessionVerificationProperties properties;

    public GrpcSessionVerificationClient(SessionVerificationProperties properties) throws SSLException {
        this.properties = properties;
        var tls = GrpcSslContexts.forClient()
                .trustManager(properties.trustCertificate().toFile())
                .keyManager(properties.certificate().toFile(), properties.privateKey().toFile())
                .build();
        channel = NettyChannelBuilder.forAddress(properties.host(), properties.port())
                .sslContext(tls)
                .disableRetry()
                .build();
        stub = IdentitySessionServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public boolean isActive(UUID userId, UUID sessionId, UUID requestId, RequestDeadline deadline) {
        if (deadline.expired()) {
            throw new SessionVerificationUnavailableException("Request deadline exceeded");
        }
        try {
            var request = CheckSessionRequest.newBuilder()
                    .setUserId(userId.toString())
                    .setSessionId(sessionId.toString())
                    .setRequestId(requestId.toString())
                    .build();
            var response = stub.withDeadline(deadline.grpcDeadline()).checkSession(request);
            return switch (response.getStatus()) {
                case SESSION_STATUS_ACTIVE -> true;
                case SESSION_STATUS_INVALID -> false;
                default -> throw new SessionVerificationUnavailableException(
                        "Unexpected session verification response");
            };
        } catch (StatusRuntimeException exception) {
            throw new SessionVerificationUnavailableException(
                    "Session verification RPC failed", exception);
        }
    }

    @PreDestroy
    public void close() {
        channel.shutdown();
        try {
            boolean terminated = channel.awaitTermination(
                    properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!terminated) {
                channel.shutdownNow();
            }
        } catch (InterruptedException exception) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
