package io.github.returnaction.fitness.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.ProfileCreationServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreateProfileIfAbsentRequest;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.WalletCreationServiceGrpc;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import org.junit.jupiter.api.Test;

/**
 * Проверяет, что исходные .proto создают ожидаемые Java message и gRPC service
 * stubs.
 */
class GrpcContractsGenerationTest {

    @Test
    void generatesIdentitySessionContract() {
        assertEquals(
                "fitness.identity.session.v1.IdentitySessionService/CheckSession",
                IdentitySessionServiceGrpc.getCheckSessionMethod().getFullMethodName());
    }

    @Test
    void generatesProfileCreationContract() {
        CreateProfileIfAbsentRequest request = CreateProfileIfAbsentRequest.newBuilder()
                .setRequestId("00000000-0000-4000-8000-000000000001")
                .setRegistrationOperationId("00000000-0000-4000-8000-000000000002")
                .setUserId("00000000-0000-4000-8000-000000000003")
                .build();

        assertEquals("00000000-0000-4000-8000-000000000003", request.getUserId());
        assertEquals(
                "fitness.accounts.profile.creation.v1.ProfileCreationService/CreateProfileIfAbsent",
                ProfileCreationServiceGrpc.getCreateProfileIfAbsentMethod().getFullMethodName());
    }

    @Test
    void generatesWalletCreationContract() {
        CreateWalletIfAbsentRequest request = CreateWalletIfAbsentRequest.newBuilder()
                .setRequestId("00000000-0000-4000-8000-000000000001")
                .setRegistrationOperationId("00000000-0000-4000-8000-000000000002")
                .setUserId("00000000-0000-4000-8000-000000000003")
                .build();

        assertEquals("00000000-0000-4000-8000-000000000003", request.getUserId());
        assertEquals(
                "fitness.coins.wallet.creation.v1.WalletCreationService/CreateWalletIfAbsent",
                WalletCreationServiceGrpc.getCreateWalletIfAbsentMethod().getFullMethodName());
    }
}
