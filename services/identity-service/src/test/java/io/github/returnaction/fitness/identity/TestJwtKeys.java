package io.github.returnaction.fitness.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Временные ключи тестов; локальные ключи разработчика не используются. */
public final class TestJwtKeys {

    private TestJwtKeys() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        try {
            Path directory = Files.createTempDirectory("identity-test-jwt-");
            Path privateKey = directory.resolve("private.pem");
            Path publicKey = directory.resolve("public.pem");
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            directory.toFile().deleteOnExit();
            privateKey.toFile().deleteOnExit();
            publicKey.toFile().deleteOnExit();
            registry.add("identity.jwt.private-key", privateKey::toString);
            registry.add("identity.jwt.public-key", publicKey::toString);
        } catch (java.io.IOException | java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot create temporary test JWT keys", exception);
        }
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }
}
