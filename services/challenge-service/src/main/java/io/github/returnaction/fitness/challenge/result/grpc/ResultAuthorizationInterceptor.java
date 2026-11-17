package io.github.returnaction.fitness.challenge.result.grpc;

import io.github.returnaction.fitness.challenge.shared.security.ServiceIdentityAuthorizer;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import javax.net.ssl.SSLSession;

/**
 * FinalizeResult доступен только доверенному источнику результата,
 * ListResultWinners — только coins-service.
 */
public final class ResultAuthorizationInterceptor implements ServerInterceptor {

    private final String resultSourceSan;
    private final String coinsSan;

    public ResultAuthorizationInterceptor(String resultSourceSan, String coinsSan) {
        this.resultSourceSan = resultSourceSan;
        this.coinsSan = coinsSan;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String method = call.getMethodDescriptor().getFullMethodName();
        String expected = method.endsWith("/ListResultWinners") ? coinsSan : resultSourceSan;
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (ServiceIdentityAuthorizer.hasUriSan(session, expected)) {
            return next.startCall(call, headers);
        }
        call.close(Status.PERMISSION_DENIED.withDescription("Service identity is not authorized"), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
