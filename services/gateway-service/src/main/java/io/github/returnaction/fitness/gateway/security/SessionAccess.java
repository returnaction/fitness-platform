package io.github.returnaction.fitness.gateway.security;

import io.github.returnaction.fitness.gateway.exception.GatewayException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import io.github.returnaction.fitness.contracts.identity.session.v1.SessionStatus;
import io.github.returnaction.fitness.gateway.configuration.GatewayProperties;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public final class SessionAccess implements AutoCloseable {
    private final ManagedChannel channel;
    private final int timeout;
    private final MeterRegistry meters;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Shared application metrics registry is injected intentionally")
    public SessionAccess(GatewayProperties settings, MeterRegistry meters) throws javax.net.ssl.SSLException {
        this.meters = meters;
        timeout = settings.sessionTimeoutMillis();
        channel = NettyChannelBuilder.forAddress(settings.sessionHost(), settings.sessionPort())
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(new File(settings.trustCertificate()))
                        .keyManager(new File(settings.certificate()), new File(settings.privateKey()))
                        .build())
                .disableRetry()
                .build();
        channel.getState(true);
    }

    public void check(AccessTokens.Principal principal, String requestId) {
        long start = System.nanoTime();
        String outcome = "unavailable";
        try {
            var result = IdentitySessionServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeout, TimeUnit.MILLISECONDS)
                    .checkSession(CheckSessionRequest.newBuilder()
                            .setRequestId(requestId)
                            .setUserId(principal.userId().toString())
                            .setSessionId(principal.sessionId().toString())
                            .build());
            if (result.getStatus() == SessionStatus.SESSION_STATUS_INVALID) {
                outcome = "invalid";
                throw GatewayException.invalid();
            }
            if (result.getStatus() != SessionStatus.SESSION_STATUS_ACTIVE)
                throw GatewayException.unavailable();
            outcome = "active";
        } catch (StatusRuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(SessionAccess.class)
                    .warn("Session verification unavailable: {}", exception.getStatus().getCode());
            throw GatewayException.unavailable();
        } finally {
            meters.timer("gateway.session.check", "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
