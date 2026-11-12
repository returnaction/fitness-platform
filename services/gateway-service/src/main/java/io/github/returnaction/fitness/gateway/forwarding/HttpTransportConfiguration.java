package io.github.returnaction.fitness.gateway.forwarding;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

@Configuration(proxyBeanMethods = false)
public class HttpTransportConfiguration {

    @Bean(destroyMethod = "close")
    CloseableHttpClient upstreamHttpClient() {
        return HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    @Bean
    ClientHttpRequestFactory upstreamRequestFactory(CloseableHttpClient upstreamHttpClient) {
        return new HttpComponentsClientHttpRequestFactory(upstreamHttpClient);
    }
}
