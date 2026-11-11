package io.github.returnaction.fitness.identity.registration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpAddressResolverTest {
    @Test
    void trustsOnlyConfiguredProxyAndUsesLastForwardedAddress() {
        var resolver = new ClientIpAddressResolver("192.0.2.1");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.5");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 198.51.100.2");
        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.5");
        request.setRemoteAddr("192.0.2.1");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.2");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "bad-host.example");
        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.1");
        assertThat(new ClientIpAddressResolver("").resolve(request)).isEqualTo("192.0.2.1");
    }

    @Test
    void refusesNonLiteralProxyAddress() {
        assertThatThrownBy(() -> new ClientIpAddressResolver("proxy.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
