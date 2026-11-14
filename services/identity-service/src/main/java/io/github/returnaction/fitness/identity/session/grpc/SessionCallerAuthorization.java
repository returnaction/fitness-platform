package io.github.returnaction.fitness.identity.session.grpc;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Проверяет URI SAN уже аутентифицированного mTLS-клиента. */
public final class SessionCallerAuthorization implements ServerInterceptor {

    private final String allowedCaller;

    public SessionCallerAuthorization(String allowedCaller) {
        if (allowedCaller == null || !allowedCaller.matches("spiffe://fitness\\.local/[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("An explicit trusted caller URI is required");
        }
        this.allowedCaller = allowedCaller;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
        var session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Client authentication required"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        try {
            var chain = session.getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate certificate) {
                var names = certificate.getSubjectAlternativeNames();
                if (names != null && names.stream()
                        .anyMatch(name -> name.size() >= 2 && Integer.valueOf(6).equals(name.get(0))
                                && allowedCaller.equals(name.get(1)))) {
                    return next.startCall(call, headers);
                }
            }
        } catch (SSLPeerUnverifiedException exception) {
            call.close(Status.UNAUTHENTICATED.withDescription("Client authentication required"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        } catch (java.security.cert.CertificateParsingException exception) {
            // Некорректный SAN не предоставляет разрешение.
        }
        call.close(Status.PERMISSION_DENIED.withDescription("Service identity is not authorized"), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
