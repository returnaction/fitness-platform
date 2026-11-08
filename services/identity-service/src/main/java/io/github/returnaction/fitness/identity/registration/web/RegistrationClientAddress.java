package io.github.returnaction.fitness.identity.registration.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Заголовкам доверяем лишь при прямом соединении от явно заданного прокси. */
@Component
public final class RegistrationClientAddress {
    private final String trustedProxy;

    public RegistrationClientAddress(@Value("${identity.registration.rate-limit.trusted-proxy-ip}") String proxy) {
        trustedProxy = proxy.isBlank() ? null : canonical(proxy);
        if (!proxy.isBlank() && trustedProxy == null) {
            throw new IllegalArgumentException("Trusted proxy must be a literal IP address");
        }
    }

    public String resolve(HttpServletRequest request) {
        String remote = canonical(request.getRemoteAddr());
        if (remote == null)
            return "unknown";
        if (trustedProxy == null || !trustedProxy.equals(remote))
            return remote;
        var headers = request.getHeaders("X-Forwarded-For");
        String last = null;
        while (headers.hasMoreElements())
            last = headers.nextElement();
        if (last == null)
            return remote;
        String[] parts = last.split(",", -1);
        String client = canonical(parts[parts.length - 1].trim());
        return client == null ? remote : client;
    }

    private static String canonical(String value) {
        if (value == null || value.isBlank() || value.contains("%"))
            return null;
        try {
            return InetAddress.ofLiteral(value).getHostAddress();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
