package io.github.returnaction.fitness.challenge.result.grpc;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * mTLS gRPC-сервер результата. В тестах отключается свойством
 * challenge.result.grpc.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "challenge.result.grpc.enabled", havingValue = "true", matchIfMissing = true)
public final class ChallengeResultGrpcServer implements SmartLifecycle {

    private final Server server;
    private volatile boolean running;

    public ChallengeResultGrpcServer(ChallengeResultGrpcService service,
                                     ChallengeResultGrpcProperties properties) throws IOException {
        if (properties.port() < 1 || properties.port() > 65535 || properties.address().isBlank()) {
            throw new IllegalArgumentException("Invalid result gRPC address or port");
        }
        server = NettyServerBuilder.forAddress(new InetSocketAddress(properties.address(), properties.port()))
                .sslContext(GrpcSslContexts.forServer(new File(properties.certificate()),
                                new File(properties.privateKey()))
                        .trustManager(new File(properties.trustCertificate()))
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(service,
                        new ResultAuthorizationInterceptor(properties.resultSourceSan(), properties.coinsSan())))
                .build();
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start result gRPC server", exception);
        }
    }

    @Override
    public void stop() {
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException _) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
