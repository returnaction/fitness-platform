package io.github.returnaction.fitness.coins.wallet.grpc;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Запускает gRPC только при явно включённой конфигурации и обязательном mTLS. */
@Component
@ConditionalOnProperty(name = "coins.grpc.enabled", havingValue = "true")
public final class SecureGrpcServer implements SmartLifecycle {
    private final Server server;
    private volatile boolean running;

    public SecureGrpcServer(
            CoinsProvisioningGrpcService service,
            @Value("${coins.grpc.address}") String address,
            @Value("${coins.grpc.port}") int port,
            @Value("${coins.grpc.certificate}") String certificate,
            @Value("${coins.grpc.private-key}") String privateKey,
            @Value("${coins.grpc.trust-certificate}") String trustCertificate)
            throws IOException {
        if (port < 1 || port > 65535 || address.isBlank()) {
            throw new IllegalArgumentException("Invalid gRPC address or port");
        }
        server = NettyServerBuilder.forAddress(new InetSocketAddress(address, port))
                .sslContext(GrpcSslContexts.forServer(new File(certificate), new File(privateKey))
                        .trustManager(new File(trustCertificate))
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(service, new IdentityAuthorizationInterceptor()))
                .build();
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start secure gRPC server", exception);
        }
    }

    @Override
    public void stop() {
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException exception) {
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
