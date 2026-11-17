package io.github.returnaction.fitness.coins.challenge;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ChallengeResultServiceGrpc;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersRequest;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.StatusProto;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * mTLS-клиент Challenge. Список победителей читается страницами; временная
 * недоступность не завершает расчёт, а постоянный отказ не повторяется.
 */
public class ChallengeResultGrpcClient implements ChallengeResultClient {

    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 1000;

    private final ChallengeResultProperties properties;
    private volatile ChallengeResultServiceGrpc.ChallengeResultServiceBlockingStub stub;
    private volatile ManagedChannel channel;

    public ChallengeResultGrpcClient(ChallengeResultProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<UUID> listWinners(UUID resultId) {
        List<UUID> winners = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            ListResultWinnersResponse response = request(resultId, cursor);
            for (String userId : response.getUserIdsList()) {
                winners.add(parse(userId));
            }
            cursor = response.getNextCursor();
            if (cursor == null || cursor.isBlank()) {
                return List.copyOf(winners);
            }
        }
        throw new ChallengeResultRejectedException("WINNERS_PAGINATION_LIMIT");
    }

    private ListResultWinnersResponse request(UUID resultId, String cursor) {
        try {
            return stub().listResultWinners(ListResultWinnersRequest.newBuilder()
                    .setResultId(resultId.toString())
                    .setCursor(cursor)
                    .setLimit(PAGE_SIZE)
                    .build());
        } catch (StatusRuntimeException exception) {
            throw classify(exception);
        }
    }

    private UUID parse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ChallengeResultRejectedException("INVALID_WINNER_ID", exception);
        }
    }

    private ChallengeResultServiceGrpc.ChallengeResultServiceBlockingStub stub() {
        ChallengeResultServiceGrpc.ChallengeResultServiceBlockingStub current = stub;
        if (current == null) {
            synchronized (this) {
                current = stub;
                if (current == null) {
                    current = createStub();
                    stub = current;
                }
            }
        }
        return current.withDeadlineAfter(properties.deadline().toMillis(), TimeUnit.MILLISECONDS);
    }

    private ChallengeResultServiceGrpc.ChallengeResultServiceBlockingStub createStub() {
        try {
            var ssl = GrpcSslContexts.forClient()
                    .trustManager(new File(properties.trustCertificate()))
                    .keyManager(new File(properties.certificate()), new File(properties.privateKey()))
                    .build();
            channel = NettyChannelBuilder.forAddress(properties.host(), properties.port())
                    .sslContext(ssl)
                    .build();
            return ChallengeResultServiceGrpc.newBlockingStub(channel);
        } catch (SSLException exception) {
            throw new IllegalStateException("Could not create Challenge gRPC channel", exception);
        }
    }

    private RuntimeException classify(StatusRuntimeException exception) {
        Status.Code code = exception.getStatus().getCode();
        if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
            return new ChallengeResultUnavailableException(exception);
        }
        String reason = reason(exception);
        return new ChallengeResultRejectedException(reason == null ? code.name() : reason, exception);
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
