package io.github.returnaction.fitness.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.AccountsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.CoinsProvisioningServiceGrpc;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletRequest;
import org.junit.jupiter.api.Test;

/** Проверяет, что исходные .proto создают ожидаемые Java message и gRPC service stubs. */
class GrpcContractsGenerationTest {

    @Test
    void generatesAccountsProvisioningContract() {
        EnsureProfileRequest request = EnsureProfileRequest.newBuilder()
                .setRequestId("00000000-0000-4000-8000-000000000001")
                .setRegistrationOperationId("00000000-0000-4000-8000-000000000002")
                .setUserId("00000000-0000-4000-8000-000000000003")
                .build();

        assertEquals("00000000-0000-4000-8000-000000000003", request.getUserId());
        assertEquals(
                "fitness.accounts.provisioning.v1.AccountsProvisioningService/EnsureProfile",
                AccountsProvisioningServiceGrpc.getEnsureProfileMethod().getFullMethodName());
    }

    @Test
    void generatesCoinsProvisioningContract() {
        EnsureWalletRequest request = EnsureWalletRequest.newBuilder()
                .setRequestId("00000000-0000-4000-8000-000000000001")
                .setRegistrationOperationId("00000000-0000-4000-8000-000000000002")
                .setUserId("00000000-0000-4000-8000-000000000003")
                .build();

        assertEquals("00000000-0000-4000-8000-000000000003", request.getUserId());
        assertEquals(
                "fitness.coins.provisioning.v1.CoinsProvisioningService/EnsureWallet",
                CoinsProvisioningServiceGrpc.getEnsureWalletMethod().getFullMethodName());
    }
}
