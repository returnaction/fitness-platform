package io.github.returnaction.fitness.coins.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.wallet.grpc.CoinsProvisioningGrpcService;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.CoinsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletRequest;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletResponse;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.ProvisioningStatus;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WalletProvisioningIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private WalletProvisioningService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CoinsProvisioningGrpcService grpcService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void retriesGrpcRequestAfterCommittedWalletResponseIsLost() throws Exception {
        var loseFirstResponse = new AtomicBoolean(true);
        var faultInjectedService = new CoinsProvisioningServiceGrpc.CoinsProvisioningServiceImplBase() {
            @Override
            public void ensureWallet(EnsureWalletRequest request, StreamObserver<EnsureWalletResponse> observer) {
                boolean drop = loseFirstResponse.getAndSet(false);
                grpcService.ensureWallet(request, new StreamObserver<>() {
                    @Override
                    public void onNext(EnsureWalletResponse response) {
                        if (!drop)
                            observer.onNext(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        observer.onError(error);
                    }

                    @Override
                    public void onCompleted() {
                        if (drop)
                            observer.onError(Status.UNAVAILABLE.asRuntimeException());
                        else
                            observer.onCompleted();
                    }
                });
            }
        };
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(faultInjectedService)
                .build()
                .start();
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .usePlaintext()
                .build();
        try {
            UUID userId = UUID.randomUUID();
            var request = EnsureWalletRequest.newBuilder()
                    .setUserId(userId.toString())
                    .setRequestId(UUID.randomUUID().toString())
                    .setRegistrationOperationId(UUID.randomUUID().toString())
                    .build();
            var stub = CoinsProvisioningServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stub.ensureWallet(request))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
            assertThat(jdbc.sql("SELECT count(*) FROM coin_wallets WHERE user_id=:id")
                    .param("id", userId)
                    .query(Long.class)
                    .single())
                    .isEqualTo(1);
            jdbc.sql("UPDATE coin_wallets SET available_balance=25 WHERE user_id=:id")
                    .param("id", userId)
                    .update();
            assertThat(stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .ensureWallet(request)
                    .getStatus())
                    .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
            assertThat(jdbc.sql("SELECT available_balance FROM coin_wallets WHERE user_id=:id")
                    .param("id", userId)
                    .query(Long.class)
                    .single())
                    .isEqualTo(25);
            assertThat(jdbc.sql("SELECT count(*) FROM coin_wallets WHERE user_id=:id")
                    .param("id", userId)
                    .query(Long.class)
                    .single())
                    .isEqualTo(1);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentRequestsCreateOneWalletAndRepeatsPreserveBalance() throws Exception {
        UUID userId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.ensureWallet(userId));
            var second = executor.submit(() -> service.ensureWallet(userId));
            first.get();
            second.get();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM coin_wallets WHERE user_id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT available_balance + locked_balance FROM coin_wallets WHERE user_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single())
                .isZero();
        jdbc.sql("UPDATE coin_wallets SET available_balance = 25, locked_balance = 4 WHERE user_id = :id")
                .param("id", userId)
                .update();
        service.ensureWallet(userId);
        assertThat(jdbc.sql("SELECT available_balance + locked_balance FROM coin_wallets WHERE user_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single())
                .isEqualTo(29);
        assertThatThrownBy(() -> jdbc.sql("UPDATE coin_wallets SET available_balance = -1 WHERE user_id = :id")
                .param("id", userId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(error.getCause().getMessage()).doesNotContain(userId.toString()));
    }
}
