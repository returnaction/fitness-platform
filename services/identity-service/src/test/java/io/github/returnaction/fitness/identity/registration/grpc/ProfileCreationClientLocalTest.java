package io.github.returnaction.fitness.identity.registration.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.ProfileCreationServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreateProfileIfAbsentRequest;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreateProfileIfAbsentResponse;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreationStatus;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Локальная проверка клиента с сертификатами step-ca, без пользовательской БД.
 */
@EnabledIfSystemProperty(named = "fitness.localStepCaSmoke", matches = "true")
class ProfileCreationClientLocalTest {
    @Test
    void identityClientConnectsUsingStepCaCertificates() throws Exception {
        File secrets = new File("../../secrets");
        var calls = new AtomicInteger();
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(GrpcSslContexts.forServer(
                        new File(secrets, "accounts-server.crt"),
                        new File(secrets, "accounts-server-pkcs8.key"))
                        .trustManager(new File(secrets, "ca.crt"))
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(new ProfileCreationServiceGrpc.ProfileCreationServiceImplBase() {
                    @Override
                    public void createProfileIfAbsent(
                                              CreateProfileIfAbsentRequest request,
                                              StreamObserver<CreateProfileIfAbsentResponse> observer) {
                        int call = calls.incrementAndGet();
                        observer.onNext(CreateProfileIfAbsentResponse.newBuilder()
                                .setUserId(call == 2 ? UUID.randomUUID().toString() : request.getUserId())
                                .setStatus(
                                        call == 3
                                                ? CreationStatus.CREATION_STATUS_UNSPECIFIED
                                                : CreationStatus.CREATION_STATUS_READY)
                                .build());
                        observer.onCompleted();
                    }
                })
                .build()
                .start();
        try {
            var client = new ProfileCreationClient(
                    "localhost",
                    server.getPort(),
                    new File(secrets, "identity-client.crt").getPath(),
                    new File(secrets, "identity-client-pkcs8.key").getPath(),
                    new File(secrets, "ca.crt").getPath());
            try {
                client.createProfileIfAbsent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
                assertThatThrownBy(() -> client.createProfileIfAbsent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> client.createProfileIfAbsent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(server.isShutdown()).isFalse();
            } finally {
                client.close();
            }
        } finally {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
