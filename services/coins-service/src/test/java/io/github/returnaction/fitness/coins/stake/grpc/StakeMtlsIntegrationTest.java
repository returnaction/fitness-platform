package io.github.returnaction.fitness.coins.stake.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.coins.stake.ReleaseStakeService;
import io.github.returnaction.fitness.coins.stake.ReserveStakeResult;
import io.github.returnaction.fitness.coins.stake.StakeCancellationService;
import io.github.returnaction.fitness.coins.stake.StakeOperationQueryService;
import io.github.returnaction.fitness.coins.stake.StakeReservationService;
import io.github.returnaction.fitness.coins.stake.StakeSetService;
import io.github.returnaction.fitness.coins.wallet.grpc.TestMtlsCertificates;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.StakeServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StakeMtlsIntegrationTest {

    @Test
    void onlyChallengeCertificateCanCallStakeService(@TempDir Path directory) throws Exception {
        var certificates = TestMtlsCertificates.create(directory);
        var reservations = mock(StakeReservationService.class);
        UUID operationId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        when(reservations.reserve(any())).thenReturn(new ReserveStakeResult(operationId,
                io.github.returnaction.fitness.coins.stake.ReserveOutcome.RESERVED, holdId));
        var service = new StakeGrpcService(reservations, mock(ReleaseStakeService.class),
                mock(StakeOperationQueryService.class), mock(StakeSetService.class),
                mock(StakeCancellationService.class));

        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(GrpcSslContexts.forServer(certificates.serverCertificate().toFile(),
                                certificates.serverPrivateKey().toFile())
                        .trustManager(certificates.caCertificate().toFile())
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(service, new ChallengeAuthorizationInterceptor()))
                .build()
                .start();
        ManagedChannel challenge = channel(server.getPort(), certificates,
                certificates.challengeCertificate(), certificates.challengePrivateKey());
        ManagedChannel identity = channel(server.getPort(), certificates,
                certificates.identityCertificate(), certificates.identityPrivateKey());
        try {
            var request = ReserveStakeRequest.newBuilder()
                    .setJoinOperationId(operationId.toString())
                    .setUserId(UUID.randomUUID().toString())
                    .setChallengeId(UUID.randomUUID().toString())
                    .setAmount(10)
                    .build();

            var response = StakeServiceGrpc.newBlockingStub(challenge)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .reserveStake(request);
            assertThat(response.getOutcome()).isEqualTo(ReserveOutcome.RESERVE_OUTCOME_RESERVED);
            assertThat(response.getHoldId()).isEqualTo(holdId.toString());

            var identityStub = StakeServiceGrpc.newBlockingStub(identity).withDeadlineAfter(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> identityStub.reserveStake(request))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
        } finally {
            challenge.shutdownNow();
            identity.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ManagedChannel channel(int port, TestMtlsCertificates certificates, Path certificate, Path key)
            throws Exception {
        return NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(certificates.caCertificate().toFile())
                        .keyManager(certificate.toFile(), key.toFile())
                        .build())
                .build();
    }
}
