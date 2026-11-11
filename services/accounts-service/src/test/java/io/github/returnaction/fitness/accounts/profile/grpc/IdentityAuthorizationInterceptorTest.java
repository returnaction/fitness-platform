package io.github.returnaction.fitness.accounts.profile.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IdentityAuthorizationInterceptorTest {
    private static final int URI_SAN_TYPE = 6;
    private final IdentityAuthorizationInterceptor interceptor = new IdentityAuthorizationInterceptor();

    @Test
    void rejectsMissingTls() {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        when(call.getAttributes()).thenReturn(Attributes.EMPTY);
        interceptor.interceptCall(call, new Metadata(), handler);
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any());
        assertThat(status.getValue().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
        verify(handler, never()).startCall(any(), any());
    }

    @Test
    void acceptsOnlyIdentityUriSan() throws Exception {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        SSLSession session = mock(SSLSession.class);
        X509Certificate certificate = mock(X509Certificate.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        when(certificate.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(URI_SAN_TYPE, "spiffe://fitness.local/identity-service")));
        when(call.getAttributes())
                .thenReturn(Attributes.newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session)
                        .build());
        interceptor.interceptCall(call, new Metadata(), handler);
        verify(handler).startCall(any(), any());
    }

    @Test
    void rejectsDifferentServiceIdentity() throws Exception {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        SSLSession session = mock(SSLSession.class);
        X509Certificate certificate = mock(X509Certificate.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        when(certificate.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(URI_SAN_TYPE, "spiffe://fitness.local/coins-service")));
        when(call.getAttributes())
                .thenReturn(Attributes.newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session)
                        .build());
        interceptor.interceptCall(call, new Metadata(), handler);
        verify(call).close(any(), any());
        verify(handler, never()).startCall(any(), any());
    }

    @Test
    void rejectsEmptyCertificateChain() throws Exception {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[0]);
        withSession(call, session);

        interceptor.interceptCall(call, new Metadata(), handler);

        assertRejected(call, handler);
    }

    @Test
    void rejectsUnverifiedPeerCertificate() throws Exception {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("peer is not verified"));
        withSession(call, session);

        interceptor.interceptCall(call, new Metadata(), handler);

        assertRejected(call, handler);
    }

    @Test
    void rejectsNonUriSubjectAlternativeName() throws Exception {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        SSLSession session = mock(SSLSession.class);
        X509Certificate certificate = mock(X509Certificate.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        when(certificate.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(2, "identity.example")));
        withSession(call, session);

        interceptor.interceptCall(call, new Metadata(), handler);

        assertRejected(call, handler);
    }

    private void withSession(ServerCall<String, String> call, SSLSession session) {
        when(call.getAttributes())
                .thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
    }

    private void assertRejected(ServerCall<String, String> call, ServerCallHandler<String, String> handler) {
        verify(call).close(any(), any());
        verify(handler, never()).startCall(any(), any());
    }

    @SuppressWarnings("unchecked")
    private ServerCall<String, String> call() {
        return mock(ServerCall.class);
    }

    @SuppressWarnings("unchecked")
    private ServerCallHandler<String, String> handler() {
        return mock(ServerCallHandler.class);
    }
}
