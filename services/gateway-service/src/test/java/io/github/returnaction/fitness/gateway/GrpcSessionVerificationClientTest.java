package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionResponse;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import io.github.returnaction.fitness.gateway.configuration.properties.SessionVerificationProperties;
import io.github.returnaction.fitness.gateway.security.session.GrpcSessionVerificationClient;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationUnavailableException;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadline;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
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
@Timeout(30)
class GrpcSessionVerificationClientTest {

    @Test
    void verifiesSessionsOverMutualTlsWithSharedDeadline(@TempDir Path dir) throws Exception {
        var caKey = keyPair();
        var caName = new X500Name("CN=Test CA");
        var ca = cert(caName, caName, caKey, caKey, null, true);
        var serverKey = keyPair();
        var serverCert = cert(caName, new X500Name("CN=localhost"), serverKey, caKey,
                new GeneralName(GeneralName.dNSName, "localhost"), false);
        var callerKey = keyPair();
        var callerCert = cert(caName, new X500Name("CN=gateway"), callerKey, caKey,
                new GeneralName(GeneralName.uniformResourceIdentifier, "spiffe://fitness.local/gateway"), false);
        var status = new AtomicInteger(1);
        var captured = new AtomicReference<CheckSessionRequest>();
        var remaining = new java.util.concurrent.atomic.AtomicLong();
        var calls = new AtomicInteger();
        var service = new IdentitySessionServiceGrpc.IdentitySessionServiceImplBase() {

            @Override
            public void checkSession(CheckSessionRequest request, StreamObserver<CheckSessionResponse> observer) {
                calls.incrementAndGet();
                captured.set(request);
                remaining.set(Context.current().getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
                if (status.get() == -1) {
                    observer.onError(Status.UNAVAILABLE.asRuntimeException());
                } else if (status.get() != -2) {
                    observer.onNext(CheckSessionResponse.newBuilder().setStatusValue(status.get()).build());
                    observer.onCompleted();
                }
            }
        };
        var ssl = GrpcSslContexts.forServer(pem(dir.resolve("server.pem"), serverCert).toFile(),
                pem(dir.resolve("server.key"), serverKey.getPrivate()).toFile())
                .trustManager(ca)
                .clientAuth(ClientAuth.REQUIRE)
                .build();
        var server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(ssl)
                .addService(service)
                .build()
                .start();
        var caFile = pem(dir.resolve("ca.pem"), ca);
        var certFile = pem(dir.resolve("client.pem"), callerCert);
        var keyFile = pem(dir.resolve("client.key"), callerKey.getPrivate());
        var settings = new SessionVerificationProperties("localhost", server.getPort(), caFile,
                certFile, keyFile, Duration.ofMillis(200));
        var client = new GrpcSessionVerificationClient(settings);
        var user = UUID.randomUUID();
        var session = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        try {
            assertThat(client.isActive(user, session, requestId, RequestDeadline.start(Duration.ofSeconds(3))))
                    .isTrue();
            assertThat(captured.get().getUserId()).isEqualTo(user.toString());
            assertThat(captured.get().getSessionId()).isEqualTo(session.toString());
            assertThat(captured.get().getRequestId()).isEqualTo(requestId.toString());
            status.set(2);
            assertThat(client.isActive(user, session, requestId, RequestDeadline.start(Duration.ofSeconds(3))))
                    .isFalse();
            for (int invalid : new int[]{0, 99, -1}) {
                status.set(invalid);
                assertThatThrownBy(() -> client.isActive(user, session, requestId,
                        RequestDeadline.start(Duration.ofSeconds(3))))
                        .isInstanceOf(SessionVerificationUnavailableException.class);
            }
            int before = calls.get();
            assertThatThrownBy(() -> client.isActive(user, session, requestId,
                    RequestDeadline.start(Duration.ofSeconds(-1))))
                    .isInstanceOf(SessionVerificationUnavailableException.class);
            assertThat(calls.get()).isEqualTo(before);
            status.set(-2);
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.isActive(user, session, requestId,
                    RequestDeadline.start(Duration.ofMillis(300))))
                    .isInstanceOf(SessionVerificationUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(remaining.get()).isPositive().isLessThan(TimeUnit.MILLISECONDS.toNanos(350));
            assertThat(calls.get()).isEqualTo(before + 1);

            // A certificate with the wrong DNS name must not pass server authentication.
            var wrongHost = new GrpcSessionVerificationClient(new SessionVerificationProperties(
                    "127.0.0.1", server.getPort(), caFile, certFile, keyFile, Duration.ofMillis(200)));
            try {
                assertThatThrownBy(() -> wrongHost.isActive(user, session, requestId,
                        RequestDeadline.start(Duration.ofSeconds(2))))
                        .isInstanceOf(SessionVerificationUnavailableException.class);
            } finally {
                wrongHost.close();
            }
            assertThat(calls.get()).isEqualTo(before + 1);
            client.close();
            assertThatThrownBy(() -> client.isActive(user, session, requestId,
                    RequestDeadline.start(Duration.ofSeconds(1))))
                    .isInstanceOf(SessionVerificationUnavailableException.class);
        } finally {
            client.close();
            server.shutdownNow();
            assertThat(server.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void missingTlsMaterialFailsBeforeAnyCall(@TempDir Path dir) {
        var settings = new SessionVerificationProperties("localhost", 1, dir.resolve("ca.pem"),
                dir.resolve("client.pem"), dir.resolve("client.key"), Duration.ofSeconds(1));
        assertThatThrownBy(() -> new GrpcSessionVerificationClient(settings)).isInstanceOf(Exception.class);
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
                                        X500Name issuer,
                                        X500Name subject,
                                        KeyPair subjectKey,
                                        KeyPair issuerKey,
                                        GeneralName san,
                                        boolean isCa) throws Exception {
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
