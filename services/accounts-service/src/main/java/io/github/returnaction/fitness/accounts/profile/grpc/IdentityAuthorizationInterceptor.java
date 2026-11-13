package io.github.returnaction.fitness.accounts.profile.grpc;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/** Разрешает gRPC-вызовы только Identity с проверенным mTLS-сертификатом. */
public final class IdentityAuthorizationInterceptor implements ServerInterceptor {

    private static final int URI_SAN_TYPE = 6;
    private static final String IDENTITY_URI = "spiffe://fitness.local/identity-service";
    private static final Status ACCESS_DENIED = Status.PERMISSION_DENIED
            .withDescription("Service identity is not authorized");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {

        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);

        if (!hasIdentityCertificate(session)) {
            return deny(call);
        }
        return next.startCall(call, headers);
    }

    private boolean hasIdentityCertificate(SSLSession session) {
        if (session == null) {
            return false;
        }

        try {
            Certificate[] certificateChain = session.getPeerCertificates();
            if (certificateChain.length == 0 || !(certificateChain[0] instanceof X509Certificate certificate)) {
                return false;
            }
            return hasIdentityUriSan(certificate);
        } catch (SSLPeerUnverifiedException | CertificateParsingException exception) {
            return false;
        }
    }

    private boolean hasIdentityUriSan(X509Certificate certificate) throws CertificateParsingException {
        Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
        return subjectAlternativeNames != null && subjectAlternativeNames.stream().anyMatch(this::isIdentityUriSan);
    }

    private boolean isIdentityUriSan(List<?> subjectAlternativeName) {
        return subjectAlternativeName.size() >= 2
                && Integer.valueOf(URI_SAN_TYPE).equals(subjectAlternativeName.getFirst())
                && IDENTITY_URI.equals(subjectAlternativeName.get(1));
    }

    private <ReqT> ServerCall.Listener<ReqT> deny(ServerCall<?, ?> call) {
        call.close(ACCESS_DENIED, new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
