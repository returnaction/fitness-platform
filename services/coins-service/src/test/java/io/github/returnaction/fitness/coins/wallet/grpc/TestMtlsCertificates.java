package io.github.returnaction.fitness.coins.wallet.grpc;

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
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Создаёт краткоживущие материалы private CA для автоматических mTLS-тестов.
 */
public final class TestMtlsCertificates {

    private static final String IDENTITY_URI = "spiffe://fitness.local/identity-service";
    private static final String SERVER_HOST = "localhost";

    private final Path caCertificate;
    private final Path serverCertificate;
    private final Path serverPrivateKey;
    private final Path identityCertificate;
    private final Path identityPrivateKey;

    private TestMtlsCertificates(Path caCertificate, Path serverCertificate, Path serverPrivateKey,
        Path identityCertificate, Path identityPrivateKey) {
        this.caCertificate = caCertificate;
        this.serverCertificate = serverCertificate;
        this.serverPrivateKey = serverPrivateKey;
        this.identityCertificate = identityCertificate;
        this.identityPrivateKey = identityPrivateKey;
    }

    public static TestMtlsCertificates create(Path directory) throws Exception {
        KeyPair caKeyPair = keyPair();
        X500Name caName = new X500Name("CN=Test CA");
        X509Certificate caCertificate = certificate(caName, caName, caKeyPair, caKeyPair, null, true);

        KeyPair serverKeyPair = keyPair();
        X509Certificate serverCertificate = certificate(caName, new X500Name("CN=" + SERVER_HOST), serverKeyPair,
                caKeyPair, new GeneralName(GeneralName.dNSName, SERVER_HOST), false);

        KeyPair identityKeyPair = keyPair();
        X509Certificate identityCertificate = certificate(caName, new X500Name("CN=identity"), identityKeyPair,
                caKeyPair, new GeneralName(GeneralName.uniformResourceIdentifier, IDENTITY_URI), false);

        return new TestMtlsCertificates(pem(directory.resolve("ca.pem"), caCertificate),
                pem(directory.resolve("server.pem"), serverCertificate),
                pem(directory.resolve("server.key"), serverKeyPair.getPrivate()),
                pem(directory.resolve("identity.pem"), identityCertificate),
                pem(directory.resolve("identity.key"), identityKeyPair.getPrivate()));
    }

    public Path caCertificate() {
        return caCertificate;
    }

    public Path serverCertificate() {
        return serverCertificate;
    }

    public Path serverPrivateKey() {
        return serverPrivateKey;
    }

    public Path identityCertificate() {
        return identityCertificate;
    }

    public Path identityPrivateKey() {
        return identityPrivateKey;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(X500Name issuer, X500Name subject, KeyPair subjectKeyPair,
        KeyPair issuerKeyPair, GeneralName subjectAlternativeName, boolean certificateAuthority) throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(issuer, new BigInteger(128, new SecureRandom()).abs(),
                Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)), subject, subjectKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(certificateAuthority));
        if (subjectAlternativeName != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(subjectAlternativeName));
        }
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeyPair.getPrivate())));
    }

    private static Path pem(Path path, Object object) throws Exception {
        StringWriter output = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(output)) {
            writer.writeObject(object);
        }
        return Files.writeString(path, output.toString());
    }
}
