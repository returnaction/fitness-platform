package io.github.returnaction.fitness.gateway.security;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import io.github.returnaction.fitness.contracts.identity.session.v1.SessionStatus;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import java.io.StringWriter;
import java.math.BigInteger;
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

import io.github.returnaction.fitness.gateway.configuration.GatewayProperties;
import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionResponse;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

class SessionAccessTest {
    @Test
    void verifiesTlsAndDoesNotCacheSessionsOrRetryFailures(@TempDir Path dir) throws Exception {
        var caKey = keyPair();
        var caName = new X500Name("CN=Test CA");
        var ca = cert(caName, caName, caKey, caKey, null, true);
        var serverKey = keyPair();
        var serverCert = cert(caName, new X500Name("CN=localhost"), serverKey, caKey,
                new GeneralName(GeneralName.dNSName, "localhost"), false);
        var clientKey = keyPair();
        var clientCert = cert(caName, new X500Name("CN=gateway"), clientKey, caKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/gateway-service"),
                false);
        var calls = new AtomicInteger();
        var mode = new AtomicInteger();
        var service = new IdentitySessionServiceGrpc.IdentitySessionServiceImplBase() {
            @Override
            public void checkSession(CheckSessionRequest request, StreamObserver<CheckSessionResponse> observer) {
                calls.incrementAndGet();
                if (mode.get() == 2) {
                    observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
                    return;
                }
                if (mode.get() == 3)
                    return; // No response: the client must enforce its deadline.
                observer.onNext(CheckSessionResponse.newBuilder()
                        .setStatus(mode.get() == 1
                                ? SessionStatus.SESSION_STATUS_INVALID
                                : SessionStatus.SESSION_STATUS_ACTIVE)
                        .build());
                observer.onCompleted();
            }
        };
        var server = NettyServerBuilder.forPort(0)
                .sslContext(GrpcSslContexts
                        .forServer(pem(dir.resolve("server.crt"), serverCert).toFile(),
                                pem(dir.resolve("server.key"), serverKey.getPrivate()).toFile())
                        .trustManager(ca)
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(service)
                .build()
                .start();
        String certificate = pem(dir.resolve("client.crt"), clientCert).toString();
        String privateKey = pem(dir.resolve("client.key"), clientKey.getPrivate()).toString();
        String trust = pem(dir.resolve("ca.crt"), ca).toString();
        var principal = new AccessTokens.Principal(UUID.randomUUID(), UUID.randomUUID());
        String requestId = UUID.randomUUID().toString();
        try {
            try (var client = new SessionAccess(settings(server.getPort(), "localhost", certificate, privateKey, trust),
                    new SimpleMeterRegistry())) {
                client.check(principal, requestId);
                mode.set(1);
                assertThatThrownBy(() -> client.check(principal, requestId)).hasMessage("invalid_access_token");
                mode.set(2);
                assertThatThrownBy(() -> client.check(principal, requestId)).hasMessage("identity_unavailable");
                mode.set(3);
                assertThatThrownBy(() -> client.check(principal, requestId)).hasMessage("identity_unavailable");
                assertThat(calls).hasValue(4);
            }
            try (var wrongHost = new SessionAccess(
                    settings(server.getPort(), "127.0.0.1", certificate, privateKey, trust),
                    new SimpleMeterRegistry())) {
                assertThatThrownBy(() -> wrongHost.check(principal, requestId)).hasMessage("identity_unavailable");
                assertThat(calls).hasValue(4);
            }
            var otherKey = keyPair();
            var otherCa = cert(new X500Name("CN=other"), new X500Name("CN=other"), otherKey, otherKey, null, true);
            String otherTrust = pem(dir.resolve("other.crt"), otherCa).toString();
            try (var wrongCa = new SessionAccess(
                    settings(server.getPort(), "localhost", certificate, privateKey, otherTrust),
                    new SimpleMeterRegistry())) {
                assertThatThrownBy(() -> wrongCa.check(principal, requestId)).hasMessage("identity_unavailable");
                assertThat(calls).hasValue(4);
            }
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private GatewayProperties settings(int port, String host, String certificate, String key, String ca) {
        return new GatewayProperties(URI.create("http://localhost:1"), URI.create("http://localhost:1/keys"),
                "issuer", "audience", 16384, 60, 60, 10000, 300, 5, 1000,
                host, port, certificate, key, ca, 1000);
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
                                        X500Name issuer, X500Name subject, KeyPair subjectKey, KeyPair issuerKey,
                                        GeneralName san, boolean isCa)
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
        if (san != null)
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san));
        return new JcaX509CertificateConverter()
                .getCertificate(
                        builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate())));
    }

}
