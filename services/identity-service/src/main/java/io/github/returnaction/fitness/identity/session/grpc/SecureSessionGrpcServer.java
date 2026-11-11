package io.github.returnaction.fitness.identity.session.grpc;

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
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class SecureSessionGrpcServer implements SmartLifecycle {

    private final Server server;
    private volatile boolean running;

    public SecureSessionGrpcServer(SessionVerificationGrpcService service,
                                   @Value("${identity.session-grpc.address}") String address,
                                   @Value("${identity.session-grpc.port}") int port,
                                   @Value("${identity.session-grpc.certificate}") String certificate,
                                   @Value("${identity.session-grpc.private-key}") String privateKey,
                                   @Value("${identity.session-grpc.trust-certificate}") String trustCertificate,
                                   @Value("${identity.session-grpc.allowed-caller}") String allowedCaller) throws IOException {
        if (port < 1 || port > 65535 || address.isBlank())
            throw new IllegalArgumentException("Invalid gRPC address");
        var authorization = new SessionCallerAuthorization(allowedCaller);
        server = NettyServerBuilder.forAddress(new InetSocketAddress(address, port))
                .sslContext(GrpcSslContexts.forServer(new File(certificate), new File(privateKey))
                        .trustManager(new File(trustCertificate))
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(service, authorization))
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
            if (!server.awaitTermination(5, TimeUnit.SECONDS))
                server.shutdownNow();
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
