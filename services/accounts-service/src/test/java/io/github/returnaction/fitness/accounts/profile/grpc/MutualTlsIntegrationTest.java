package io.github.returnaction.fitness.accounts.profile.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.returnaction.fitness.accounts.profile.service.ProfileProvisioningService;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
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
        ProfileProvisioningService provisioning = mock(ProfileProvisioningService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "test",
                            Map.of(
                                    "accounts.grpc.enabled",
                                    "true",
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
            context.registerBean(ProfileProvisioningService.class, () -> provisioning);
            context.register(AccountsProvisioningGrpcService.class, SecureGrpcServer.class);
            context.refresh();
            assertThat(context.getBean(SecureGrpcServer.class).isRunning()).isTrue();
            ManagedChannel anonymous = channel(port, ca, null, null);
            try {
                var request = EnsureProfileRequest.newBuilder()
                        .setUserId(UUID.randomUUID().toString())
                        .setRequestId(UUID.randomUUID().toString())
                        .setRegistrationOperationId(UUID.randomUUID().toString())
                        .build();
                assertThatThrownBy(() -> AccountsProvisioningServiceGrpc.newBlockingStub(anonymous)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .ensureProfile(request));
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
                                    "accounts.grpc.enabled",
                                    "true",
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
            context.registerBean(ProfileProvisioningService.class, () -> provisioning);
            context.register(AccountsProvisioningGrpcService.class, SecureGrpcServer.class);
            assertThatThrownBy(context::refresh).isInstanceOf(org.springframework.beans.BeansException.class);
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
    void onlyIdentityWithTrustedClientCertificateCanProvision() throws Exception {
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
        ProfileProvisioningService provisioning = mock(ProfileProvisioningService.class);
        Server server = NettyServerBuilder.forPort(0)
                .sslContext(GrpcSslContexts.configure(SslContextBuilder.forServer(serverKey.getPrivate(), serverCert))
                        .trustManager(ca)
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(
                        new AccountsProvisioningGrpcService(provisioning), new IdentityAuthorizationInterceptor()))
                .build()
                .start();
        ManagedChannel identity = channel(server.getPort(), ca, identityKey, identityCert);
        ManagedChannel other = channel(server.getPort(), ca, otherKey, otherCert);
        ManagedChannel anonymous = channel(server.getPort(), ca, null, null);
        try {
            UUID user = UUID.randomUUID();
            EnsureProfileRequest request = EnsureProfileRequest.newBuilder()
                    .setUserId(user.toString())
                    .setRequestId(UUID.randomUUID().toString())
                    .setRegistrationOperationId(UUID.randomUUID().toString())
                    .build();
            assertThat(AccountsProvisioningServiceGrpc.newBlockingStub(identity)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .ensureProfile(request)
                            .getStatus())
                    .isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
            verify(provisioning).ensureProfile(user);
            assertThatThrownBy(() -> AccountsProvisioningServiceGrpc.newBlockingStub(other)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .ensureProfile(request))
                    .satisfies(error ->
                            assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            UUID anonymousUser = UUID.randomUUID();
            EnsureProfileRequest anonymousRequest =
                    request.toBuilder().setUserId(anonymousUser.toString()).build();
            assertThatThrownBy(() -> AccountsProvisioningServiceGrpc.newBlockingStub(anonymous)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .ensureProfile(anonymousRequest))
                    .satisfies(error ->
                            assertThat(Status.fromThrowable(error).isOk()).isFalse());
            verify(provisioning, never()).ensureProfile(anonymousUser);
        } finally {
            identity.shutdownNow();
            other.shutdownNow();
            anonymous.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ManagedChannel channel(int port, X509Certificate ca, KeyPair key, X509Certificate certificate)
            throws Exception {
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
            X500Name issuer, X500Name subject, KeyPair subjectKey, KeyPair issuerKey, GeneralName san, boolean ca)
            throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                issuer,
                new BigInteger(128, new SecureRandom()).abs(),
                Date.from(now.minusSeconds(60)),
                Date.from(now.plusSeconds(3600)),
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
