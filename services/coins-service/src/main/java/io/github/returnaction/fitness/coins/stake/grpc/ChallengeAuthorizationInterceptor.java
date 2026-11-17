package io.github.returnaction.fitness.coins.stake.grpc;

import io.github.returnaction.fitness.coins.shared.security.ServiceIdentityAuthorizer;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import javax.net.ssl.SSLSession;

/**
 * Разрешает stake-команды только сервису Challenge по URI SAN.
 */
public final class ChallengeAuthorizationInterceptor implements ServerInterceptor {

    private static final String CHALLENGE = "spiffe://fitness.local/challenge-service";

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (ServiceIdentityAuthorizer.hasUriSan(session, CHALLENGE)) {
            return next.startCall(call, headers);
        }
        call.close(Status.PERMISSION_DENIED.withDescription("Service identity is not authorized"), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
