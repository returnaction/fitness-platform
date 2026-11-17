package io.github.returnaction.fitness.challenge.result.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.challenge.service.ChallengeResultService;
import io.github.returnaction.fitness.challenge.service.FinalResult;
import io.github.returnaction.fitness.challenge.service.ResultWinnersPage;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ChallengeResultServiceGrpc;
import io.github.returnaction.fitness.contracts.challenge.results.v1.FinalizeResultRequest;
import io.github.returnaction.fitness.contracts.challenge.results.v1.FinalizeResultResponse;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersRequest;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersResponse;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ResultOutcome;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChallengeResultMtlsIntegrationTest {

    @Test
    void authorizesResultSourceAndCoinsBySan(@TempDir Path directory) throws Exception {
        ChallengeResultTestCertificates certificates = ChallengeResultTestCertificates.create(directory);
        int port = freePort();
        UUID resultId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        ChallengeResultService resultService = mock(ChallengeResultService.class);
        when(resultService.finalizeResult(any())).thenReturn(new FinalResult(
                resultId, challengeId, 1, "SINGLE_WINNER", 1, "ab".repeat(32)));
        when(resultService.listWinners(any(), anyString(), anyInt()))
                .thenReturn(new ResultWinnersPage(List.of(winnerId), ""));

        ChallengeResultGrpcServer server = new ChallengeResultGrpcServer(
                new ChallengeResultGrpcService(resultService),
                new ChallengeResultGrpcProperties(true, "127.0.0.1", port,
                        certificates.serverCertificate().toString(),
                        certificates.serverPrivateKey().toString(),
                        certificates.caCertificate().toString(),
                        "spiffe://fitness.local/result-source",
                        "spiffe://fitness.local/coins-service"));
        server.start();
        ManagedChannel resultSource = null;
        ManagedChannel coins = null;
        ManagedChannel anonymous = null;
        try {
            resultSource = channel(port, certificates, certificates.resultSourceCertificate(),
                    certificates.resultSourcePrivateKey());
            coins = channel(port, certificates, certificates.coinsCertificate(),
                    certificates.coinsPrivateKey());
            anonymous = anonymousChannel(port, certificates);

            var resultSourceStub = ChallengeResultServiceGrpc.newBlockingStub(resultSource)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            var coinsStub = ChallengeResultServiceGrpc.newBlockingStub(coins)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            var anonymousStub = ChallengeResultServiceGrpc.newBlockingStub(anonymous)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);

            FinalizeResultResponse finalized = resultSourceStub.finalizeResult(finalizeRequest(resultId,
                    challengeId, winnerId));
            assertThat(finalized.getResultId()).isEqualTo(resultId.toString());
            assertThat(finalized.getWinnersDigest()).isEqualTo("ab".repeat(32));

            assertThatThrownBy(() -> resultSourceStub.listResultWinners(winnersRequest(resultId)))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));

            ListResultWinnersResponse winners = coinsStub.listResultWinners(winnersRequest(resultId));
            assertThat(winners.getUserIdsList()).containsExactly(winnerId.toString());

            assertThatThrownBy(() -> coinsStub.finalizeResult(finalizeRequest(resultId, challengeId, winnerId)))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));

            assertThatThrownBy(() -> anonymousStub.listResultWinners(winnersRequest(resultId)))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.UNAVAILABLE));
        } finally {
            shutdown(resultSource);
            shutdown(coins);
            shutdown(anonymous);
            server.stop();
        }
    }

    private static ManagedChannel channel(int port, ChallengeResultTestCertificates certificates,
                                          Path certificate, Path privateKey) throws Exception {
        return NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(certificates.caCertificate().toFile())
                        .keyManager(certificate.toFile(), privateKey.toFile())
                        .build())
                .build();
    }

    private static ManagedChannel anonymousChannel(int port, ChallengeResultTestCertificates certificates)
            throws Exception {
        return NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(certificates.caCertificate().toFile())
                        .build())
                .build();
    }

    private static FinalizeResultRequest finalizeRequest(UUID resultId, UUID challengeId, UUID winnerId) {
        return FinalizeResultRequest.newBuilder()
                .setResultId(resultId.toString())
                .setChallengeId(challengeId.toString())
                .setSetVersion(1)
                .setSetDigest("cd".repeat(32))
                .setOutcome(ResultOutcome.RESULT_OUTCOME_SINGLE_WINNER)
                .setRuleVersion("equal-pool-v1")
                .addWinnerUserIds(winnerId.toString())
                .build();
    }

    private static ListResultWinnersRequest winnersRequest(UUID resultId) {
        return ListResultWinnersRequest.newBuilder()
                .setResultId(resultId.toString())
                .setCursor("")
                .setLimit(10)
                .build();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void shutdown(ManagedChannel channel) {
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
