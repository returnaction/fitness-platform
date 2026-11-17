package io.github.returnaction.fitness.challenge.coins;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOperationConflictException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsRejectedException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.StakeServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.StatusProto;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;

/**
 * mTLS-клиент stake-команд Coins. Deadline меньше gateway 7s; UNAVAILABLE и
 * DEADLINE_EXCEEDED считаются неизвестным исходом и повторяются один раз с тем
 * же operation ID. Постоянные ошибки классифицируются отдельно.
 */
public class CoinsStakeClient {

    private static final int RETRIES = 1;

    private final CoinsStakeProperties properties;
    private final Duration deadline;
    private volatile StakeServiceGrpc.StakeServiceBlockingStub stub;
    private volatile ManagedChannel channel;

    public CoinsStakeClient(CoinsStakeProperties properties) {
        this.properties = properties;
        this.deadline = properties.deadline();
    }

    CoinsStakeClient(StakeServiceGrpc.StakeServiceBlockingStub stub, Duration deadline) {
        this.properties = null;
        this.stub = stub;
        this.deadline = deadline;
    }

    public ReserveStakeResponse reserveStake(ReserveStakeRequest request) {
        return call(() -> stub().reserveStake(request));
    }

    public ReleaseStakeResponse releaseStake(ReleaseStakeRequest request) {
        return call(() -> stub().releaseStake(request));
    }

    public GetStakeOperationResponse getStakeOperation(GetStakeOperationRequest request) {
        return call(() -> stub().getStakeOperation(request));
    }

    public BeginStakeSetResponse beginStakeSet(BeginStakeSetRequest request) {
        return call(() -> stub().beginStakeSet(request));
    }

    public PutStakeBatchResponse putStakeBatch(PutStakeBatchRequest request) {
        return call(() -> stub().putStakeBatch(request));
    }

    public FinalizeStakeSetResponse finalizeStakeSet(FinalizeStakeSetRequest request) {
        return call(() -> stub().finalizeStakeSet(request));
    }

    public FreezeStakeSetResponse freezeStakeSet(FreezeStakeSetRequest request) {
        return call(() -> stub().freezeStakeSet(request));
    }

    public CompleteStakeCancellationResponse completeStakeCancellation(
            CompleteStakeCancellationRequest request) {
        return call(() -> stub().completeStakeCancellation(request));
    }

    private <T> T call(Supplier<T> action) {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            try {
                return action.get();
            } catch (StatusRuntimeException exception) {
                if (isUnknown(exception) && attempt < RETRIES) {
                    continue;
                }
                throw classify(exception);
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private StakeServiceGrpc.StakeServiceBlockingStub stub() {
        StakeServiceGrpc.StakeServiceBlockingStub current = stub;
        if (current == null) {
            synchronized (this) {
                current = stub;
                if (current == null) {
                    current = createStub();
                    stub = current;
                }
            }
        }
        return current.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private StakeServiceGrpc.StakeServiceBlockingStub createStub() {
        try {
            var ssl = GrpcSslContexts.forClient()
                    .trustManager(new File(properties.trustCertificate()))
                    .keyManager(new File(properties.certificate()), new File(properties.privateKey()))
                    .build();
            channel = NettyChannelBuilder.forAddress(properties.host(), properties.port())
                    .sslContext(ssl)
                    .build();
            return StakeServiceGrpc.newBlockingStub(channel);
        } catch (SSLException exception) {
            throw new IllegalStateException("Could not create Coins gRPC channel", exception);
        }
    }

    private boolean isUnknown(StatusRuntimeException exception) {
        Status.Code code = exception.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
    }

    private RuntimeException classify(StatusRuntimeException exception) {
        if (isUnknown(exception)) {
            return new CoinsOutcomeUnknownException(exception);
        }
        String reason = reason(exception);
        if (exception.getStatus().getCode() == Status.Code.ALREADY_EXISTS
                && "OPERATION_ID_CONFLICT".equals(reason)) {
            return new CoinsOperationConflictException(exception);
        }
        return new CoinsRejectedException(exception.getStatus().getCode().name(), reason, exception);
    }

    private String reason(StatusRuntimeException exception) {
        var status = StatusProto.fromThrowable(exception);
        if (status == null) {
            return null;
        }
        for (Any detail : status.getDetailsList()) {
            if (detail.is(ErrorInfo.class)) {
                try {
                    return detail.unpack(ErrorInfo.class).getReason();
                } catch (InvalidProtocolBufferException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
