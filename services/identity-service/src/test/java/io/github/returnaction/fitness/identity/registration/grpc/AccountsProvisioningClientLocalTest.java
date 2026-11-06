package io.github.returnaction.fitness.identity.registration.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileResponse;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Локальная проверка клиента с сертификатами step-ca, без пользовательской БД. */
@EnabledIfSystemProperty(named = "fitness.localStepCaSmoke", matches = "true")
class AccountsProvisioningClientLocalTest {
    @Test
    void identityClientConnectsUsingStepCaCertificates() throws Exception {
        File secrets = new File("../../secrets");
        Server server = NettyServerBuilder.forPort(0)
                .sslContext(GrpcSslContexts.forServer(
                                new File(secrets, "accounts-server.crt"),
                                new File(secrets, "accounts-server-pkcs8.key"))
                        .trustManager(new File(secrets, "ca.crt"))
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(new AccountsProvisioningServiceGrpc.AccountsProvisioningServiceImplBase() {
                    @Override
                    public void ensureProfile(
                            EnsureProfileRequest request, StreamObserver<EnsureProfileResponse> observer) {
                        observer.onNext(EnsureProfileResponse.newBuilder()
                                .setUserId(request.getUserId())
                                .setStatus(ProvisioningStatus.PROVISIONING_STATUS_READY)
                                .build());
                        observer.onCompleted();
                    }
                })
                .build()
                .start();
        try {
            var client = new AccountsProvisioningClient(
                    "localhost",
                    server.getPort(),
                    new File(secrets, "identity-client.crt").getPath(),
                    new File(secrets, "identity-client-pkcs8.key").getPath(),
                    new File(secrets, "ca.crt").getPath());
            try {
                client.ensureProfile(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
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
