package io.github.returnaction.fitness.identity.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.identity.security.jwt.AccessTokenSigner;
import io.github.returnaction.fitness.identity.security.jwt.PublicJwtKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccessTokenConfigurationTest {
    @TempDir
    Path directory;

    private ApplicationContextRunner context(String keyPath, String publicPath) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AccessTokenConfiguration.class)
                .withPropertyValues(
                        "identity.jwt.enabled=true",
                        "identity.jwt.private-key=" + keyPath,
                        "identity.jwt.public-key=" + publicPath,
                        "identity.jwt.key-id=test-key",
                        "identity.jwt.issuer=test-issuer",
                        "identity.jwt.audience=test-audience");
    }

    @Test
    void loadsValidPkcs8KeyAndFailsClosedForMissingKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        byte[] encoded = pair.getPrivate().getEncoded();
        Path key = directory.resolve("key.pem");
        Path publicKey = directory.resolve("public.pem");
        Files.writeString(
                key,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                        + "\n-----END PRIVATE KEY-----\n");
        Files.writeString(
                publicKey,
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[]{'\n'})
                                .encodeToString(pair.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n");
        context(key.toString(), publicKey.toString()).run(ctx -> {
            assertThat(ctx).hasSingleBean(AccessTokenSigner.class);
            var jwks = ctx.getBean(PublicJwtKeys.class).jwks();
            var entries = (java.util.List<?>) jwks.get("keys");
            var entry = (java.util.Map<?, ?>) entries.getFirst();
            assertThat(entry.containsKey("d")).isFalse();
            assertThat(entry.get("kid")).isEqualTo("test-key");
        });
        var different = generator.generateKeyPair();
        Path otherKey = directory.resolve("other-public.pem");
        Files.writeString(
                otherKey,
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[]{'\n'})
                                .encodeToString(different.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n");
        context(key.toString(), otherKey.toString()).run(ctx -> assertThat(ctx).hasFailed());
        context(directory.resolve("missing.pem").toString(), publicKey.toString())
                .run(ctx -> assertThat(ctx).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(AccessTokenConfiguration.class)
                .withPropertyValues("identity.jwt.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AccessTokenSigner.class));
    }
}
