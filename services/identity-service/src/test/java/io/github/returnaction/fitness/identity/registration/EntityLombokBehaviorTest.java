package io.github.returnaction.fitness.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.identity.registration.entity.operation.RegistrationOperationEntity;
import io.github.returnaction.fitness.identity.registration.entity.role.UserRoleId;
import io.github.returnaction.fitness.identity.registration.entity.user.UserCredentialEntity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityLombokBehaviorTest {

    @Test
    void operationKeepsDigestDefensivelyCopied() {
        byte[] digest = {1, 2};
        var operation = new RegistrationOperationEntity(UUID.randomUUID(), digest, UUID.randomUUID());
        digest[0] = 9;
        byte[] returned = operation.getIdempotencyKeyDigest();
        returned[1] = 9;
        assertThat(operation.getIdempotencyKeyDigest()).containsExactly((byte) 1, (byte) 2);
    }

    @Test
    void roleIdEqualityUsesBothKeys() {
        UUID userId = UUID.randomUUID();
        var first = new UserRoleId(userId, (short) 1);
        var same = new UserRoleId(userId, (short) 1);
        var different = new UserRoleId(userId, (short) 2);
        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(different);
    }

    @Test
    void credentialToStringDoesNotRevealPasswordHash() {
        var credential = new UserCredentialEntity(UUID.randomUUID(), "secret-hash");
        assertThat(credential.getPasswordHash()).isEqualTo("secret-hash");
        assertThat(credential.toString()).doesNotContain("secret-hash");
    }
}
