package io.github.returnaction.fitness.coins.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.wallet.grpc.WalletCreationGrpcService;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.coins.wallet.grpc.TestMtlsCertificates;
import io.github.returnaction.fitness.coins.wallet.entity.WalletEntity;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import java.net.ServerSocket;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.persistence.PersistenceException;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.WalletCreationServiceGrpc;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentRequest;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentResponse;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreationStatus;
import io.github.returnaction.fitness.coins.wallet.service.WalletCreationService;
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
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WalletCreationIntegrationTest {

    @MockitoBean
    private SecureGrpcServer managedGrpcServer;

    @Autowired
    private WalletRepository wallets;

    private WalletEntity wallet(UUID userId) {
        return wallets.findById(userId).orElseThrow();
    }
    private long queryNumber(String sql, UUID userId) {
        return ((Number) entityManager.createNativeQuery(sql).setParameter("id", userId).getSingleResult()).longValue();
    }

    private void executeUpdate(String sql, UUID userId) {
        transactions.executeWithoutResult(
                status -> entityManager.createNativeQuery(sql).setParameter("id", userId).executeUpdate());
    }
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private WalletCreationService service;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private WalletCreationGrpcService grpcService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void retriesGrpcRequestAfterCommittedWalletResponseIsLost() throws Exception {
        var loseFirstResponse = new AtomicBoolean(true);
        var faultInjectedService = new WalletCreationServiceGrpc.WalletCreationServiceImplBase() {

            @Override
            public void createWalletIfAbsent(CreateWalletIfAbsentRequest request,
                                             StreamObserver<CreateWalletIfAbsentResponse> observer) {
                boolean drop = loseFirstResponse.getAndSet(false);
                grpcService.createWalletIfAbsent(request, new StreamObserver<>() {

                    @Override
                    public void onNext(CreateWalletIfAbsentResponse response) {
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
            var request = CreateWalletIfAbsentRequest.newBuilder()
                    .setUserId(userId.toString())
                    .setRequestId(UUID.randomUUID().toString())
                    .setRegistrationOperationId(UUID.randomUUID().toString())
                    .build();
            var stub = WalletCreationServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stub.createWalletIfAbsent(request))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
            assertThat(queryNumber("SELECT count(*) FROM coin_wallets WHERE user_id=:id", userId))
                    .isEqualTo(1);
            executeUpdate("UPDATE coin_wallets SET available_balance=25 WHERE user_id=:id", userId);
            assertThat(stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .createWalletIfAbsent(request)
                    .getStatus())
                    .isEqualTo(CreationStatus.CREATION_STATUS_READY);
            assertThat(queryNumber("SELECT available_balance FROM coin_wallets WHERE user_id=:id", userId))
                    .isEqualTo(25);
            assertThat(queryNumber("SELECT count(*) FROM coin_wallets WHERE user_id=:id", userId))
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
            var first = executor.submit(() -> service.createWalletIfAbsent(userId));
            var second = executor.submit(() -> service.createWalletIfAbsent(userId));
            first.get();
            second.get();
        }
        assertThat(queryNumber("SELECT count(*) FROM coin_wallets WHERE user_id = :id", userId))
                .isEqualTo(1);
        WalletEntity initial = wallet(userId);
        assertThat(initial.getAvailableBalance()).isZero();
        assertThat(initial.getLockedBalance()).isZero();
        assertThat(initial.getCreatedAt()).isNotNull();
        assertThat(initial.getUpdatedAt()).isEqualTo(initial.getCreatedAt());
        executeUpdate("UPDATE coin_wallets SET available_balance = 25, locked_balance = 4 WHERE user_id = :id", userId);
        service.createWalletIfAbsent(userId);
        WalletEntity repeated = wallet(userId);
        assertThat(repeated.getAvailableBalance()).isEqualTo(25);
        assertThat(repeated.getLockedBalance()).isEqualTo(4);
        assertThat(repeated.getCreatedAt()).isEqualTo(initial.getCreatedAt());
        assertThat(repeated.getUpdatedAt()).isEqualTo(initial.getUpdatedAt());
        assertThatThrownBy(
                () -> executeUpdate("UPDATE coin_wallets SET available_balance = -1 WHERE user_id = :id", userId))
                .isInstanceOf(PersistenceException.class)
                .satisfies(error -> assertThat(error.getCause().getMessage()).doesNotContain(userId.toString()));
        assertThatThrownBy(
                () -> executeUpdate("UPDATE coin_wallets SET locked_balance = -1 WHERE user_id = :id", userId))
                .isInstanceOf(PersistenceException.class);
        assertThat(wallet(userId).getAvailableBalance()).isEqualTo(25);
        assertThat(wallet(userId).getLockedBalance()).isEqualTo(4);
    }

    @Test
    void secureServerCommitsWalletForIdentityAndRejectsOtherClient(@TempDir Path directory) throws Exception {
        var certificates = TestMtlsCertificates.create(directory);
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var server = new SecureGrpcServer(grpcService, "127.0.0.1", port,
                certificates.serverCertificate().toString(), certificates.serverPrivateKey().toString(),
                certificates.caCertificate().toString());
        var identity = NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(certificates.caCertificate().toFile())
                        .keyManager(certificates.identityCertificate().toFile(),
                                certificates.identityPrivateKey().toFile())
                        .build())
                .build();
        // Доверенный сертификат сервера не содержит URI SAN Identity и не даёт права на
        // команду.
        var other = NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(certificates.caCertificate().toFile())
                        .keyManager(certificates.serverCertificate().toFile(), certificates.serverPrivateKey().toFile())
                        .build())
                .build();
        try {
            server.start();
            UUID userId = UUID.randomUUID();
            var request = CreateWalletIfAbsentRequest.newBuilder()
                    .setUserId(userId.toString())
                    .setRequestId(UUID.randomUUID().toString())
                    .setRegistrationOperationId(UUID.randomUUID().toString())
                    .build();
            var stub = WalletCreationServiceGrpc.newBlockingStub(identity);
            assertThat(stub.withDeadlineAfter(5, TimeUnit.SECONDS).createWalletIfAbsent(request).getStatus())
                    .isEqualTo(CreationStatus.CREATION_STATUS_READY);
            assertThat(wallet(userId).getAvailableBalance()).isZero();
            assertThat(wallet(userId).getLockedBalance()).isZero();
            assertThat(stub.withDeadlineAfter(5, TimeUnit.SECONDS).createWalletIfAbsent(request).getUserId())
                    .isEqualTo(userId.toString());
            UUID rejectedUser = UUID.randomUUID();
            var rejectedRequest = request.toBuilder().setUserId(rejectedUser.toString()).build();
            assertThatThrownBy(() -> WalletCreationServiceGrpc.newBlockingStub(other)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .createWalletIfAbsent(rejectedRequest))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
            assertThat(wallets.existsById(rejectedUser)).isFalse();
        } finally {
            identity.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            other.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.stop();
        }
    }
}
