package io.github.returnaction.fitness.accounts.profile.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.ProfileCreationServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreateProfileIfAbsentRequest;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreationStatus;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class MutualTlsIntegrationTest {

    @Test
    void springStartsActualServerAndRejectsMissingCertificates(@TempDir Path directory) throws Exception {
        KeyPair caKey = keyPair();
        X500Name caName = new X500Name("CN=Test CA");
        X509Certificate ca = certificate(caName, caName, caKey, caKey, null, true);
        KeyPair serverKey = keyPair();
        X509Certificate cert = certificate(
                caName,
                new X500Name("CN=localhost"),
                serverKey,
                caKey,
                new GeneralName(GeneralName.dNSName, "localhost"),
                false);
        Path caFile = pem(directory.resolve("ca.pem"), ca);
        Path certFile = pem(directory.resolve("server.pem"), cert);
        Path keyFile = pem(directory.resolve("server.key"), serverKey.getPrivate());
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProfileCreationService creation = mock(ProfileCreationService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "test",
                            Map.of(
                                    "accounts.grpc.address",
                                    "127.0.0.1",
                                    "accounts.grpc.port",
                                    port,
                                    "accounts.grpc.certificate",
                                    certFile.toString(),
                                    "accounts.grpc.private-key",
                                    keyFile.toString(),
                                    "accounts.grpc.trust-certificate",
                                    caFile.toString())));
            context.registerBean(ProfileCreationService.class, () -> creation);
            context.register(ProfileCreationGrpcService.class, SecureGrpcServer.class);
            context.refresh();
            assertThat(context.getBean(SecureGrpcServer.class).isRunning()).isTrue();
            ManagedChannel anonymous = channel(port, ca, null, null);
            try {
                var request = CreateProfileIfAbsentRequest.newBuilder()
                        .setUserId(UUID.randomUUID().toString())
                        .setRequestId(UUID.randomUUID().toString())
                        .setRegistrationOperationId(UUID.randomUUID().toString())
                        .build();
                assertThatThrownBy(() -> ProfileCreationServiceGrpc.newBlockingStub(anonymous)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .createProfileIfAbsent(request));
            } finally {
                anonymous.shutdownNow();
            }
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "test",
                            Map.of(
                                    "accounts.grpc.address",
                                    "127.0.0.1",
                                    "accounts.grpc.port",
                                    port,
                                    "accounts.grpc.certificate",
                                    directory.resolve("missing.pem").toString(),
                                    "accounts.grpc.private-key",
                                    keyFile.toString(),
                                    "accounts.grpc.trust-certificate",
                                    caFile.toString())));
            context.registerBean(ProfileCreationService.class, () -> creation);
            context.register(ProfileCreationGrpcService.class, SecureGrpcServer.class);
            assertThatThrownBy(context::refresh).isInstanceOf(BeansException.class);
        }
    }

    private static Path pem(Path path, Object object) throws Exception {
        StringWriter output = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(output)) {
            writer.writeObject(object);
        }
        return Files.writeString(path, output.toString());
    }

    @Test
    void onlyIdentityWithTrustedClientCertificateCanCreateProfile() throws Exception {
        KeyPair caKey = keyPair();
        X500Name caName = new X500Name("CN=Test CA");
        X509Certificate ca = certificate(caName, caName, caKey, caKey, null, true);
        KeyPair serverKey = keyPair();
        X509Certificate serverCert = certificate(
                caName,
                new X500Name("CN=localhost"),
                serverKey,
                caKey,
                new GeneralName(GeneralName.dNSName, "localhost"),
                false);
        KeyPair identityKey = keyPair();
        X509Certificate identityCert = certificate(
                caName,
                new X500Name("CN=identity"),
                identityKey,
                caKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/identity-service"),
                false);
        KeyPair otherKey = keyPair();
        X509Certificate otherCert = certificate(
                caName,
                new X500Name("CN=other"),
                otherKey,
                caKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/coins-service"),
                false);
        KeyPair expiredKey = keyPair();
        X509Certificate expiredCert = certificate(
                caName,
                new X500Name("CN=expired"),
                expiredKey,
                caKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/identity-service"),
                false,
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600));
        KeyPair foreignCaKey = keyPair();
        X500Name foreignCaName = new X500Name("CN=Foreign CA");
        KeyPair foreignKey = keyPair();
        X509Certificate foreignCert = certificate(
                foreignCaName,
                new X500Name("CN=foreign"),
                foreignKey,
                foreignCaKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/identity-service"),
                false);
        ProfileCreationService creation = mock(ProfileCreationService.class);
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(GrpcSslContexts.configure(SslContextBuilder.forServer(serverKey.getPrivate(), serverCert))
                        .trustManager(ca)
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(
                        new ProfileCreationGrpcService(creation), new IdentityAuthorizationInterceptor()))
                .build()
                .start();
        ManagedChannel identity = channel(server.getPort(), ca, identityKey, identityCert);
        ManagedChannel other = channel(server.getPort(), ca, otherKey, otherCert);
        ManagedChannel anonymous = channel(server.getPort(), ca, null, null);
        ManagedChannel expired = channel(server.getPort(), ca, expiredKey, expiredCert);
        ManagedChannel foreign = channel(server.getPort(), ca, foreignKey, foreignCert);
        try {
            UUID user = UUID.randomUUID();
            CreateProfileIfAbsentRequest request = CreateProfileIfAbsentRequest.newBuilder()
                    .setUserId(user.toString())
                    .setRequestId(UUID.randomUUID().toString())
                    .setRegistrationOperationId(UUID.randomUUID().toString())
                    .build();
            assertThat(ProfileCreationServiceGrpc.newBlockingStub(identity)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .createProfileIfAbsent(request)
                    .getStatus())
                    .isEqualTo(CreationStatus.CREATION_STATUS_READY);
            verify(creation).createIfAbsent(user);
            assertThatThrownBy(() -> ProfileCreationServiceGrpc.newBlockingStub(other)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .createProfileIfAbsent(request))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
            UUID anonymousUser = UUID.randomUUID();
            CreateProfileIfAbsentRequest anonymousRequest = request.toBuilder()
                    .setUserId(anonymousUser.toString())
                    .build();
            assertThatThrownBy(() -> ProfileCreationServiceGrpc.newBlockingStub(anonymous)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .createProfileIfAbsent(anonymousRequest))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).isOk()).isFalse());
            verify(creation, never()).createIfAbsent(anonymousUser);
            for (ManagedChannel invalid : List.of(expired, foreign)) {
                UUID rejectedUser = UUID.randomUUID();
                var rejectedRequest = request.toBuilder().setUserId(rejectedUser.toString()).build();
                assertThatThrownBy(() -> ProfileCreationServiceGrpc.newBlockingStub(invalid)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .createProfileIfAbsent(rejectedRequest))
                        .satisfies(error -> assertThat(Status.fromThrowable(error).isOk()).isFalse());
                verify(creation, never()).createIfAbsent(rejectedUser);
            }
        } finally {
            identity.shutdownNow();
            other.shutdownNow();
            anonymous.shutdownNow();
            expired.shutdownNow();
            foreign.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ManagedChannel channel(int port,
                                          X509Certificate ca,
                                          KeyPair key,
                                          X509Certificate certificate) throws Exception {
        var ssl = GrpcSslContexts.forClient().trustManager(ca);
        if (key != null) {
            ssl.keyManager(key.getPrivate(), certificate);
        }
        return NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(ssl.build())
                .build();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(
                                               X500Name issuer,
                                               X500Name subject,
                                               KeyPair subjectKey,
                                               KeyPair issuerKey,
                                               GeneralName san,
                                               boolean ca) throws Exception {
        Instant now = Instant.now();
        return certificate(
                issuer, subject, subjectKey, issuerKey, san, ca, now.minusSeconds(60), now.plusSeconds(3600));
    }

    private static X509Certificate certificate(
                                               X500Name issuer,
                                               X500Name subject,
                                               KeyPair subjectKey,
                                               KeyPair issuerKey,
                                               GeneralName san,
                                               boolean ca,
                                               Instant validFrom,
                                               Instant validUntil) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(
                issuer,
                new BigInteger(128, new SecureRandom()).abs(),
                Date.from(validFrom),
                Date.from(validUntil),
                subject,
                subjectKey.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (san != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san));
        }
        return new JcaX509CertificateConverter()
                .getCertificate(
                        builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate())));
    }
}
