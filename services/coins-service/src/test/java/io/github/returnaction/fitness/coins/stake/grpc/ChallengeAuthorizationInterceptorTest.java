package io.github.returnaction.fitness.coins.stake.grpc;

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
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChallengeAuthorizationInterceptorTest {

    private final ChallengeAuthorizationInterceptor interceptor = new ChallengeAuthorizationInterceptor();

    @Test
    void rejectsMissingTls() {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        when(call.getAttributes()).thenReturn(Attributes.EMPTY);

        interceptor.interceptCall(call, new Metadata(), handler);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        verify(handler, never()).startCall(any(), any());
    }

    @Test
    void acceptsOnlyChallengeUriSan() throws Exception {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        tls(call, "spiffe://fitness.local/challenge-service");

        interceptor.interceptCall(call, new Metadata(), handler);

        verify(handler).startCall(any(), any());
        verify(call, never()).close(any(), any());
    }

    @Test
    void rejectsOtherServiceIdentity() throws Exception {
        ServerCall<String, String> call = call();
        ServerCallHandler<String, String> handler = handler();
        tls(call, "spiffe://fitness.local/identity-service");

        interceptor.interceptCall(call, new Metadata(), handler);

        verify(call).close(any(), any());
        verify(handler, never()).startCall(any(), any());
    }

    private static void tls(ServerCall<String, String> call, String uri) throws Exception {
        SSLSession session = mock(SSLSession.class);
        X509Certificate certificate = mock(X509Certificate.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, uri)));
        when(call.getAttributes())
                .thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<String, String> call() {
        return mock(ServerCall.class);
    }

    @SuppressWarnings("unchecked")
    private static ServerCallHandler<String, String> handler() {
        return mock(ServerCallHandler.class);
    }
}
