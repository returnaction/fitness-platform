package io.github.returnaction.fitness.identity.security.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import io.github.returnaction.fitness.identity.security.jwt.AccessTokenSigner;
import io.github.returnaction.fitness.identity.security.jwt.PublicJwtKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Загружает и проверяет локальные или серверные JWT-ключи. */
@Configuration
public class AccessTokenConfiguration {
    @Bean
    JwtKeyMaterial jwtKeyMaterial(@Value("${identity.jwt.private-key}") String privateKey,
                                  @Value("${identity.jwt.public-key}") String publicKey)
            throws IOException, GeneralSecurityException {
        var factory = KeyFactory.getInstance("EC");
        var loaded = factory.generatePrivate(new PKCS8EncodedKeySpec(readPem(privateKey, "PRIVATE KEY")));
        var publicLoaded = factory.generatePublic(new X509EncodedKeySpec(readPem(publicKey, "PUBLIC KEY")));
        if (!(loaded instanceof ECPrivateKey key) || !(publicLoaded instanceof ECPublicKey verificationKey)) {
            throw new IllegalArgumentException("Invalid EC key pair");
        }
        Signature signature = Signature.getInstance("SHA256withECDSA");
        byte[] challenge = "fitness-jwt-key-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        signature.initSign(key);
        signature.update(challenge);
        byte[] signed = signature.sign();
        signature.initVerify(verificationKey);
        signature.update(challenge);
        if (!signature.verify(signed)) {
            throw new IllegalArgumentException("JWT keys do not match");
        }
        return new JwtKeyMaterial(key, verificationKey);
    }

    @Bean
    AccessTokenSigner accessTokenSigner(JwtKeyMaterial keys,
                                        @Value("${identity.jwt.key-id}") String keyId,
                                        @Value("${identity.jwt.issuer}") String issuer,
                                        @Value("${identity.jwt.audience}") String audience) {
        return new AccessTokenSigner(keys.privateKey(), keyId, issuer, audience, Clock.systemUTC());
    }

    @Bean
    PublicJwtKeys publicJwtKeys(JwtKeyMaterial keys, @Value("${identity.jwt.key-id}") String keyId) {
        var jwk = new ECKey.Builder(Curve.P_256, keys.publicKey())
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .build();
        return new PublicJwtKeys(jwk.toJSONObject());
    }

    private byte[] readPem(String path, String type) throws IOException {
        String pem = Files.readString(Path.of(path)).trim();
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end) || pem.length() > 4096) {
            throw new IllegalArgumentException("Invalid PEM key");
        }
        String encoded = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }

    record JwtKeyMaterial(ECPrivateKey privateKey, ECPublicKey publicKey) {
    }
}
