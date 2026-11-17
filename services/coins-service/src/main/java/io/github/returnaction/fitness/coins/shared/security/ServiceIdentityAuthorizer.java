package io.github.returnaction.fitness.coins.shared.security;

import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Проверяет URI SAN проверенного клиентского TLS-сертификата.
 */
public final class ServiceIdentityAuthorizer {

    private ServiceIdentityAuthorizer() {
    }

    public static boolean hasUriSan(SSLSession session, String expectedUri) {
        if (session == null) {
            return false;
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)) {
                return false;
            }
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            return names != null && names.stream()
                    .anyMatch(name -> name.size() >= 2 && Integer.valueOf(6).equals(name.get(0))
                            && expectedUri.equals(name.get(1)));
        } catch (SSLPeerUnverifiedException | CertificateParsingException _) {
            return false;
        }
    }
}
