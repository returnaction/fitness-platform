package io.github.returnaction.fitness.challenge.result.grpc;

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
 * Создаёт краткоживущие mTLS-материалы для автоматического теста результата.
 */
final class ChallengeResultTestCertificates {

    private static final String RESULT_SOURCE_URI = "spiffe://fitness.local/result-source";
    private static final String COINS_URI = "spiffe://fitness.local/coins-service";
    private static final String SERVER_HOST = "localhost";

    private final Path caCertificate;
    private final Path serverCertificate;
    private final Path serverPrivateKey;
    private final Path resultSourceCertificate;
    private final Path resultSourcePrivateKey;
    private final Path coinsCertificate;
    private final Path coinsPrivateKey;

    private ChallengeResultTestCertificates(Path caCertificate, Path serverCertificate, Path serverPrivateKey,
                                             Path resultSourceCertificate, Path resultSourcePrivateKey,
                                             Path coinsCertificate, Path coinsPrivateKey) {
        this.caCertificate = caCertificate;
        this.serverCertificate = serverCertificate;
        this.serverPrivateKey = serverPrivateKey;
        this.resultSourceCertificate = resultSourceCertificate;
        this.resultSourcePrivateKey = resultSourcePrivateKey;
        this.coinsCertificate = coinsCertificate;
        this.coinsPrivateKey = coinsPrivateKey;
    }

    static ChallengeResultTestCertificates create(Path directory) throws Exception {
        KeyPair caKeyPair = keyPair();
        X500Name caName = new X500Name("CN=Challenge Result Test CA");
        X509Certificate caCertificate = certificate(caName, caName, caKeyPair, caKeyPair, null, true);

        KeyPair serverKeyPair = keyPair();
        X509Certificate serverCertificate = certificate(caName, new X500Name("CN=" + SERVER_HOST),
                serverKeyPair, caKeyPair, new GeneralName(GeneralName.dNSName, SERVER_HOST), false);

        KeyPair resultSourceKeyPair = keyPair();
        X509Certificate resultSourceCertificate = certificate(caName, new X500Name("CN=result-source"),
                resultSourceKeyPair, caKeyPair,
                new GeneralName(GeneralName.uniformResourceIdentifier, RESULT_SOURCE_URI), false);

        KeyPair coinsKeyPair = keyPair();
        X509Certificate coinsCertificate = certificate(caName, new X500Name("CN=coins"),
                coinsKeyPair, caKeyPair,
                new GeneralName(GeneralName.uniformResourceIdentifier, COINS_URI), false);

        return new ChallengeResultTestCertificates(
                pem(directory.resolve("ca.pem"), caCertificate),
                pem(directory.resolve("server.pem"), serverCertificate),
                pem(directory.resolve("server.key"), serverKeyPair.getPrivate()),
                pem(directory.resolve("result-source.pem"), resultSourceCertificate),
                pem(directory.resolve("result-source.key"), resultSourceKeyPair.getPrivate()),
                pem(directory.resolve("coins.pem"), coinsCertificate),
                pem(directory.resolve("coins.key"), coinsKeyPair.getPrivate()));
    }

    Path caCertificate() {
        return caCertificate;
    }

    Path serverCertificate() {
        return serverCertificate;
    }

    Path serverPrivateKey() {
        return serverPrivateKey;
    }

    Path resultSourceCertificate() {
        return resultSourceCertificate;
    }

    Path resultSourcePrivateKey() {
        return resultSourcePrivateKey;
    }

    Path coinsCertificate() {
        return coinsCertificate;
    }

    Path coinsPrivateKey() {
        return coinsPrivateKey;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(X500Name issuer, X500Name subject, KeyPair subjectKeyPair,
                                               KeyPair issuerKeyPair, GeneralName subjectAlternativeName,
                                               boolean certificateAuthority) throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(issuer,
                new BigInteger(128, new SecureRandom()).abs(),
                Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)),
                subject, subjectKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(certificateAuthority));
        if (subjectAlternativeName != null) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(subjectAlternativeName));
        }
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                        .build(issuerKeyPair.getPrivate())));
    }

    private static Path pem(Path path, Object object) throws Exception {
        StringWriter output = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(output)) {
            writer.writeObject(object);
        }
        return Files.writeString(path, output.toString());
    }
}
