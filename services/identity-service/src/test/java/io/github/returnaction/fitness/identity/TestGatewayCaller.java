package io.github.returnaction.fitness.identity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MockMvc has no TLS handshake; supplies the container's verified-peer
 * attribute.
 */
public final class TestGatewayCaller {

    private TestGatewayCaller() {
    }

    public static MockHttpServletRequestBuilder post(String path) {
        return MockMvcRequestBuilders.post(path)
                .secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[]{certificate(
                        "spiffe://fitness.local/gateway-service")});
    }

    public static MockHttpServletRequestBuilder get(String path) {
        return MockMvcRequestBuilders.get(path)
                .secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[]{
                        certificate("spiffe://fitness.local/gateway-service")});
    }

    public static X509Certificate certificate(String identity) {
        var certificate = mock(X509Certificate.class);
        try {
            when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, identity)));
        } catch (CertificateParsingException exception) {
            throw new IllegalStateException(exception);
        }
        return certificate;
    }
}
