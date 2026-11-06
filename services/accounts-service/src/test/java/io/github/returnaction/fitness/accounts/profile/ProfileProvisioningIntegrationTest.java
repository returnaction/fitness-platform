package io.github.returnaction.fitness.accounts.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.accounts.profile.grpc.AccountsProvisioningGrpcService;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.service.NicknameGenerator;
import io.github.returnaction.fitness.accounts.profile.service.ProfileProvisioningService;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    ProfileProvisioningService service;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    NicknameGenerator nicknameGenerator;

    @Autowired
    AccountsProvisioningGrpcService grpcService;

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
                var stub =
                        AccountsProvisioningServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                assertThat(stub.ensureProfile(request).getStatus())
                        .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
                assertThat(stub.ensureProfile(request).getStatus())
                        .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
                assertThat(jdbc.sql("SELECT count(*) FROM user_profiles WHERE user_id = :id")
                                .param("id", userId)
                                .query(Long.class)
                                .single())
                        .isEqualTo(1);
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
        jdbc.sql("INSERT INTO user_profiles (user_id, public_profile_id, nickname) VALUES (:id, :publicId, :nickname)")
                .param("id", existing)
                .param("publicId", UUID.randomUUID())
                .param("nickname", "Player_000000000001")
                .update();
        org.mockito.Mockito.doReturn("Player_000000000001", "Player_000000000002")
                .when(nicknameGenerator)
                .generate();
        UUID userId = UUID.randomUUID();
        service.ensureProfile(userId);
        assertThat(jdbc.sql("SELECT nickname FROM user_profiles WHERE user_id = :id")
                        .param("id", userId)
                        .query(String.class)
                        .single())
                .isEqualTo("Player_000000000002");
    }

    @Test
    void databaseRejectsCaseInsensitiveDuplicateAndInvalidVisibility() {
        jdbc.sql("INSERT INTO user_profiles (user_id, public_profile_id, nickname) VALUES (:id, :publicId, :nickname)")
                .param("id", UUID.randomUUID())
                .param("publicId", UUID.randomUUID())
                .param("nickname", "UniqueNick")
                .update();
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO user_profiles (user_id, public_profile_id, nickname) VALUES (:id, :publicId, :nickname)")
                        .param("id", UUID.randomUUID())
                        .param("publicId", UUID.randomUUID())
                        .param("nickname", "uniquenick")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO user_profiles (user_id, public_profile_id, nickname, visibility) VALUES (:id, :publicId, :nickname, 'INVALID')")
                        .param("id", UUID.randomUUID())
                        .param("publicId", UUID.randomUUID())
                        .param("nickname", "AnotherNick")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentRepeatsCreateOneProfileWithoutOverwriting() throws Exception {
        UUID userId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.ensureProfile(userId));
            var second = executor.submit(() -> service.ensureProfile(userId));
            first.get();
            second.get();
        }
        String nickname = jdbc.sql("SELECT nickname FROM user_profiles WHERE user_id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        assertThat(nickname).matches("Player_[0-9]{12}");
        service.ensureProfile(userId);
        assertThat(jdbc.sql("SELECT count(*) FROM user_profiles WHERE user_id = :id")
                        .param("id", userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT nickname FROM user_profiles WHERE user_id = :id")
                        .param("id", userId)
                        .query(String.class)
                        .single())
                .isEqualTo(nickname);
    }
}
