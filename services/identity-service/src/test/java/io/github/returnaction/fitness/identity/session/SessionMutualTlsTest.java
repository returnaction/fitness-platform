package io.github.returnaction.fitness.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import io.github.returnaction.fitness.contracts.identity.session.v1.SessionStatus;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
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

class SessionMutualTlsTest {
    @Test
    void onlyConfiguredCallerCanCheckSession(@TempDir Path dir) throws Exception {
        var caKey = keyPair();
        var caName = new X500Name("CN=Test CA");
        var ca = cert(caName, caName, caKey, caKey, null, true);
        var serverKey = keyPair();
        var serverCert = cert(
                caName,
                new X500Name("CN=localhost"),
                serverKey,
                caKey,
                new GeneralName(GeneralName.dNSName, "localhost"),
                false);
        var callerKey = keyPair();
        var callerCert = cert(
                caName,
                new X500Name("CN=gateway"),
                callerKey,
                caKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/test-gateway"),
                false);
        var otherKey = keyPair();
        var otherCert = cert(
                caName,
                new X500Name("CN=other"),
                otherKey,
                caKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/coins-service"),
                false);
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var repo = mock(SessionVerificationRepository.class);
        when(repo.check(any(), any())).thenReturn(new SessionVerificationRepository.Result(true, Instant.now()));
        var service = new SessionVerificationGrpcService(repo);
        var server = new SecureSessionGrpcServer(
                service,
                "127.0.0.1",
                port,
                pem(dir.resolve("server.pem"), serverCert).toString(),
                pem(dir.resolve("server.key"), serverKey.getPrivate()).toString(),
                pem(dir.resolve("ca.pem"), ca).toString(),
                "spiffe://fitness.local/test-gateway");
        server.start();
        var trusted = channel(port, ca, callerKey, callerCert);
        var other = channel(port, ca, otherKey, otherCert);
        var anonymous = channel(port, ca, null, null);
        try {
            var request = CheckSessionRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setUserId(UUID.randomUUID().toString())
                    .setSessionId(UUID.randomUUID().toString())
                    .build();
            assertThat(IdentitySessionServiceGrpc.newBlockingStub(trusted)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .checkSession(request)
                            .getStatus())
                    .isEqualTo(SessionStatus.SESSION_STATUS_ACTIVE);
            assertThatThrownBy(() -> IdentitySessionServiceGrpc.newBlockingStub(other)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .checkSession(request))
                    .satisfies(error ->
                            assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            assertThatThrownBy(() -> IdentitySessionServiceGrpc.newBlockingStub(anonymous)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .checkSession(request));
            assertThatThrownBy(() -> new SessionCallerAuthorization("")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            trusted.shutdownNow();
            other.shutdownNow();
            anonymous.shutdownNow();
            server.stop();
        }
    }

    private static ManagedChannel channel(int port, X509Certificate ca, KeyPair key, X509Certificate cert)
            throws Exception {
        var ssl = GrpcSslContexts.forClient().trustManager(ca);
        if (key != null) ssl.keyManager(key.getPrivate(), cert);
        return NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(ssl.build())
                .build();
    }

    private static Path pem(Path path, Object object) throws Exception {
        var out = new StringWriter();
        try (var writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        }
        return Files.writeString(path, out.toString());
    }

    private static KeyPair keyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate cert(
            X500Name issuer, X500Name subject, KeyPair subjectKey, KeyPair issuerKey, GeneralName san, boolean isCa)
            throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                issuer,
                new BigInteger(128, new SecureRandom()).abs(),
                Date.from(now.minusSeconds(60)),
                Date.from(now.plusSeconds(3600)),
                subject,
                subjectKey.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
        if (san != null) builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san));
        return new JcaX509CertificateConverter()
                .getCertificate(
                        builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate())));
    }
}
