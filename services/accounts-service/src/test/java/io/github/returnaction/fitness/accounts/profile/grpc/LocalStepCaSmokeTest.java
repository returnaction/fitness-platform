package io.github.returnaction.fitness.accounts.profile.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.returnaction.fitness.accounts.profile.service.ProfileProvisioningService;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Ручная локальная проверка файлов step-ca; в CI не запускается. */
@EnabledIfSystemProperty(named = "fitness.localStepCaSmoke", matches = "true")
class LocalStepCaSmokeTest {
    @Test
    void connectsWithLocallyIssuedCertificates() throws Exception {
        File directory = new File("../../secrets");
        File ca = new File(directory, "ca.crt");
        ProfileProvisioningService provisioning = mock(ProfileProvisioningService.class);
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(GrpcSslContexts.forServer(
                                new File(directory, "accounts-server.crt"),
                                new File(directory, "accounts-server-pkcs8.key"))
                        .trustManager(ca)
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(
                        new AccountsProvisioningGrpcService(provisioning), new IdentityAuthorizationInterceptor()))
                .build()
                .start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", server.getPort())
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(ca)
                        .keyManager(
                                new File(directory, "identity-client.crt"),
                                new File(directory, "identity-client-pkcs8.key"))
                        .build())
                .build();
        try {
            UUID user = UUID.randomUUID();
            var request = EnsureProfileRequest.newBuilder()
                    .setUserId(user.toString())
                    .setRequestId(UUID.randomUUID().toString())
                    .setRegistrationOperationId(UUID.randomUUID().toString())
                    .build();
            assertThat(AccountsProvisioningServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .ensureProfile(request)
                            .getStatus())
                    .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
            verify(provisioning).ensureProfile(user);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
