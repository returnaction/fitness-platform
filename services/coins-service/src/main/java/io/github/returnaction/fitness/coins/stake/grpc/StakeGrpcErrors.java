package io.github.returnaction.fitness.coins.stake.grpc;

import static java.util.Map.entry;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.github.returnaction.fitness.coins.stake.exception.ActiveHoldsRemainException;
import io.github.returnaction.fitness.coins.stake.exception.DuplicateStakeEntryException;
import io.github.returnaction.fitness.coins.stake.exception.HoldChallengeMismatchException;
import io.github.returnaction.fitness.coins.stake.exception.HoldNotReleasableException;
import io.github.returnaction.fitness.coins.stake.exception.HoldNotFoundException;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.OperationNotFoundException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancellationConflictException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancelledException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetIncompleteException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetMismatchException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealInProgressException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealNotFoundException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSealedException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetSettledException;
import io.github.returnaction.fitness.coins.stake.exception.WalletNotFoundException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.util.Map;
import org.springframework.dao.DataAccessException;

/**
 * Преобразует доменные исключения Coins в gRPC Status с ErrorInfo.reason.
 */
final class StakeGrpcErrors {

    private static final String DOMAIN = "coins.fitness.internal";

    private static final Map<Class<? extends RuntimeException>, GrpcError> MAPPINGS = Map.ofEntries(
            entry(IllegalArgumentException.class, new GrpcError(Status.Code.INVALID_ARGUMENT, "INVALID_ARGUMENT")),
            entry(OperationIdConflictException.class,
                    new GrpcError(Status.Code.ALREADY_EXISTS, "OPERATION_ID_CONFLICT")),
            entry(WalletNotFoundException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "WALLET_NOT_FOUND")),
            entry(HoldNotFoundException.class, new GrpcError(Status.Code.NOT_FOUND, "HOLD_NOT_FOUND")),
            entry(OperationNotFoundException.class,
                    new GrpcError(Status.Code.NOT_FOUND, "OPERATION_NOT_FOUND")),
            entry(StakeSetSealNotFoundException.class,
                    new GrpcError(Status.Code.NOT_FOUND, "OPERATION_NOT_FOUND")),
            entry(HoldNotReleasableException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "HOLD_NOT_RELEASABLE")),
            entry(HoldChallengeMismatchException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "HOLD_CHALLENGE_MISMATCH")),
            entry(StakeSetSealInProgressException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "STAKE_SET_SEAL_IN_PROGRESS")),
            entry(StakeSetIncompleteException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "STAKE_SET_INCOMPLETE")),
            entry(StakeSetMismatchException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "STAKE_SET_MISMATCH")),
            entry(StakeSetSealedException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "STAKE_SET_SEALED")),
            entry(StakeSetCancelledException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "STAKE_SET_CANCELLED")),
            entry(StakeSetSettledException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "STAKE_SET_SETTLED")),
            entry(ActiveHoldsRemainException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "ACTIVE_HOLDS_REMAIN")),
            entry(StakeSetCancellationConflictException.class,
                    new GrpcError(Status.Code.FAILED_PRECONDITION, "CANCELLATION_CONFLICT")),
            entry(DuplicateStakeEntryException.class,
                    new GrpcError(Status.Code.INVALID_ARGUMENT, "DUPLICATE_STAKE_ENTRY")),
            entry(DataAccessException.class, new GrpcError(Status.Code.UNAVAILABLE, "COINS_UNAVAILABLE")));

    private StakeGrpcErrors() {
    }

    static StatusRuntimeException map(RuntimeException exception) {
        GrpcError error = MAPPINGS.entrySet().stream()
                .filter(entry -> entry.getKey().isInstance(exception))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> new GrpcError(Status.Code.INTERNAL, "INTERNAL_ERROR"));
        return status(error);
    }

    private static StatusRuntimeException status(GrpcError error) {
        var status = com.google.rpc.Status.newBuilder()
                .setCode(error.code().value())
                .setMessage(message(error.code()))
                .addDetails(Any.pack(ErrorInfo.newBuilder()
                        .setReason(error.reason())
                        .setDomain(DOMAIN)
                        .build()))
                .build();
        return StatusProto.toStatusRuntimeException(status);
    }

    private static String message(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "Invalid stake request.";
            case ALREADY_EXISTS -> "Operation ID conflicts with previous request.";
            case NOT_FOUND -> "Stake resource not found.";
            case FAILED_PRECONDITION -> "Stake operation cannot be applied in the current state.";
            case UNAVAILABLE -> "Coins service is temporarily unavailable.";
            default -> "Unexpected server error.";
        };
    }

    private record GrpcError(Status.Code code, String reason) {
    }
}
