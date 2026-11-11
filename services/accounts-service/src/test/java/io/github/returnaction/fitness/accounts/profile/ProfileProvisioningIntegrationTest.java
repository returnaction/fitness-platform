package io.github.returnaction.fitness.accounts.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import io.github.returnaction.fitness.accounts.profile.grpc.AccountsProvisioningGrpcService;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.helpers.NicknameGenerator;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileResponse;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.EntityManager;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ProfileProvisioningIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ProfileCreationService service;

    @Autowired
    ProfileRepository profileRepository;

    @Autowired
    EntityManager entityManager;

    @MockitoSpyBean
    NicknameGenerator nicknameGenerator;

    @Autowired
    AccountsProvisioningGrpcService grpcService;

    @MockitoBean
    SecureGrpcServer secureGrpcServer;

    @Test
    void retriesGrpcRequestAfterCommittedProfileResponseIsLost() throws Exception {
        var loseFirstResponse = new AtomicBoolean(true);
        var faultInjectedService = new AccountsProvisioningServiceGrpc.AccountsProvisioningServiceImplBase() {
            @Override
            public void ensureProfile(EnsureProfileRequest request, StreamObserver<EnsureProfileResponse> observer) {
                boolean drop = loseFirstResponse.getAndSet(false);
                grpcService.ensureProfile(request, new StreamObserver<>() {
                    @Override
                    public void onNext(EnsureProfileResponse response) {
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
            var request = EnsureProfileRequest.newBuilder()
                    .setUserId(userId.toString())
                    .setRequestId(UUID.randomUUID().toString())
                    .setRegistrationOperationId(UUID.randomUUID().toString())
                    .build();
            var stub = AccountsProvisioningServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stub.ensureProfile(request))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
            String nickname = profile(userId).getNickname();
            assertThat(stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .ensureProfile(request)
                    .getStatus())
                    .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
            assertThat(profile(userId).getNickname()).isEqualTo(nickname);
            assertThat(profileCount(userId)).isOne();
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "fitness.localStepCaSmoke", matches = "true")
    void identityClientCreatesCommittedProfileThroughAccountsMtls() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        File secrets = new File("../../secrets");
        SecureGrpcServer server = new SecureGrpcServer(
                grpcService,
                "127.0.0.1",
                port,
                new File(secrets, "accounts-server.crt").getPath(),
                new File(secrets, "accounts-server-pkcs8.key").getPath(),
                new File(secrets, "ca.crt").getPath());
        server.start();
        try {
            var channel = NettyChannelBuilder.forAddress("localhost", port)
                    .sslContext(GrpcSslContexts.forClient()
                            .trustManager(new File(secrets, "ca.crt"))
                            .keyManager(
                                    new File(secrets, "identity-client.crt"),
                                    new File(secrets, "identity-client-pkcs8.key"))
                            .build())
                    .build();
            try {
                UUID userId = UUID.randomUUID();
                UUID operationId = UUID.randomUUID();
                var request = EnsureProfileRequest.newBuilder()
                        .setUserId(userId.toString())
                        .setRegistrationOperationId(operationId.toString())
                        .setRequestId(UUID.randomUUID().toString())
                        .build();
                var stub = AccountsProvisioningServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS);
                assertThat(stub.ensureProfile(request).getStatus())
                        .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
                assertThat(stub.ensureProfile(request).getStatus())
                        .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
                assertThat(profile(userId).getUserId()).isEqualTo(userId);
            } finally {
                channel.shutdownNow();
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void retriesWhenGeneratedNicknameIsAlreadyTaken() {
        UUID existing = UUID.randomUUID();
        profileRepository.saveAndFlush(
                new UserProfileEntity(existing, UUID.randomUUID(), "Player_000000000001"));
        doReturn("Player_000000000001", "Player_000000000002")
                .when(nicknameGenerator)
                .generate();
        UUID userId = UUID.randomUUID();
        service.createIfAbsent(userId);
        assertThat(profile(userId).getNickname()).isEqualTo("Player_000000000002");
    }

    @Test
    void databaseRejectsCaseInsensitiveDuplicateNickname() {
        profileRepository.saveAndFlush(
                new UserProfileEntity(UUID.randomUUID(), UUID.randomUUID(), "UniqueNick"));

        assertThatThrownBy(() -> profileRepository.saveAndFlush(
                new UserProfileEntity(UUID.randomUUID(), UUID.randomUUID(), "uniquenick")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(error.getCause().getMessage()).doesNotContain("uniquenick"));
    }

    @Test
    void concurrentRepeatsCreateOneProfileWithoutOverwriting() throws Exception {
        UUID userId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.createIfAbsent(userId));
            var second = executor.submit(() -> service.createIfAbsent(userId));
            first.get();
            second.get();
        }
        String nickname = profile(userId).getNickname();
        assertThat(nickname).matches("Player_[0-9]{12}");
        service.createIfAbsent(userId);
        assertThat(profile(userId).getNickname()).isEqualTo(nickname);
        assertThat(profileCount(userId)).isOne();
    }

    private UserProfileEntity profile(UUID userId) {
        return profileRepository.findById(userId).orElseThrow();
    }

    private long profileCount(UUID userId) {
        return entityManager
                .createQuery("SELECT COUNT(profile) FROM UserProfileEntity profile WHERE profile.userId = :userId", Long.class)
                .setParameter("userId", userId)
                .getSingleResult();
    }
}
