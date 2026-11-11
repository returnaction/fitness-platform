package io.github.returnaction.fitness.coins.wallet.grpc;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Разрешает creation только service identity Identity из проверенного
 * TLS-сертификата.
 */
public final class IdentityAuthorizationInterceptor implements ServerInterceptor {
    private static final String IDENTITY = "spiffe://fitness.local/identity-service";

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                                                                 ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (!authorized(session)) {
            call.close(Status.PERMISSION_DENIED.withDescription("Service identity is not authorized"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return next.startCall(call, headers);
    }

    private boolean authorized(SSLSession session) {
        if (session == null) {
            return false;
        }
        try {
            java.security.cert.Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)) {
                return false;
            }
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            return names != null
                    && names.stream()
                            .anyMatch(name -> name.size() >= 2
                                    && Integer.valueOf(6).equals(name.get(0))
                                    && IDENTITY.equals(name.get(1)));
        } catch (SSLPeerUnverifiedException | java.security.cert.CertificateParsingException exception) {
            return false;
        }
    }
}
