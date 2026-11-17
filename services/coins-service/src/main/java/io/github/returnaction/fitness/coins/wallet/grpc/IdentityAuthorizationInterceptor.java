package io.github.returnaction.fitness.coins.wallet.grpc;

import io.github.returnaction.fitness.coins.shared.security.ServiceIdentityAuthorizer;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import javax.net.ssl.SSLSession;

/**
 * Разрешает создание кошелька только Identity по URI SAN проверенного
 * TLS-сертификата.
 */
public final class IdentityAuthorizationInterceptor implements ServerInterceptor {

    private static final String IDENTITY = "spiffe://fitness.local/identity-service";

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
        ServerCallHandler<Q, R> next) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (ServiceIdentityAuthorizer.hasUriSan(session, IDENTITY)) {
            return next.startCall(call, headers);
        }
        call.close(Status.PERMISSION_DENIED.withDescription("Service identity is not authorized"), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
