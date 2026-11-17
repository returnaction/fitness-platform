package io.github.returnaction.fitness.challenge.result.grpc;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.github.returnaction.fitness.challenge.service.ResultFailureException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.springframework.dao.DataAccessException;

/**
 * Доменные ошибки результата → gRPC Status с ErrorInfo.reason.
 */
final class ChallengeResultGrpcErrors {

    private static final String DOMAIN = "challenge.fitness.internal";

    private ChallengeResultGrpcErrors() {
    }

    static StatusRuntimeException map(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException) {
            return status(Status.Code.INVALID_ARGUMENT, "INVALID_ARGUMENT");
        }
        if (exception instanceof ResultFailureException failure) {
            return switch (failure.getReason()) {
                case INVALID_REQUEST -> status(Status.Code.INVALID_ARGUMENT, "INVALID_ARGUMENT");
                case RESULT_ID_CONFLICT -> status(Status.Code.ALREADY_EXISTS, "RESULT_ID_CONFLICT");
                case CHALLENGE_RESULT_EXISTS -> status(Status.Code.ALREADY_EXISTS, "CHALLENGE_RESULT_EXISTS");
                case RESULT_NOT_FOUND -> status(Status.Code.NOT_FOUND, "RESULT_NOT_FOUND");
                case UNSUPPORTED_OUTCOME -> status(Status.Code.FAILED_PRECONDITION, "UNSUPPORTED_OUTCOME");
                case CHALLENGE_NOT_ACTIVE -> status(Status.Code.FAILED_PRECONDITION, "CHALLENGE_NOT_ACTIVE");
                case STAKE_SET_NOT_SEALED -> status(Status.Code.FAILED_PRECONDITION, "STAKE_SET_NOT_SEALED");
                case STAKE_SET_MISMATCH -> status(Status.Code.FAILED_PRECONDITION, "STAKE_SET_MISMATCH");
                case WINNER_COUNT_MISMATCH -> status(Status.Code.FAILED_PRECONDITION, "WINNER_COUNT_MISMATCH");
                case WINNER_NOT_PARTICIPANT -> status(Status.Code.FAILED_PRECONDITION, "WINNER_NOT_PARTICIPANT");
            };
        }
        if (exception instanceof DataAccessException) {
            return status(Status.Code.UNAVAILABLE, "CHALLENGE_UNAVAILABLE");
        }
        return status(Status.Code.INTERNAL, "INTERNAL_ERROR");
    }

    private static StatusRuntimeException status(Status.Code code, String reason) {
        var status = com.google.rpc.Status.newBuilder()
                .setCode(code.value())
                .setMessage(message(code))
                .addDetails(Any.pack(ErrorInfo.newBuilder()
                        .setReason(reason)
                        .setDomain(DOMAIN)
                        .build()))
                .build();
        return StatusProto.toStatusRuntimeException(status);
    }

    private static String message(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "Invalid result request.";
            case ALREADY_EXISTS -> "Result conflicts with previous request.";
            case NOT_FOUND -> "Result not found.";
            case FAILED_PRECONDITION -> "Result cannot be finalized in the current state.";
            case UNAVAILABLE -> "Challenge service is temporarily unavailable.";
            default -> "Unexpected server error.";
        };
    }
}
