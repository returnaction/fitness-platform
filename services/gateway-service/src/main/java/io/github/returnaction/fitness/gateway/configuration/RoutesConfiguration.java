package io.github.returnaction.fitness.gateway.configuration;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import io.github.returnaction.fitness.gateway.web.GatewayFilter;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import java.util.List;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class RoutesConfiguration {

    @Bean(destroyMethod = "close")
    CloseableHttpClient upstreamClient(GatewayProperties settings) {
        var timeout = Timeout.ofMilliseconds(settings.httpTimeoutMillis());
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(64)
                .setMaxConnPerRoute(64)
                .setDefaultConnectionConfig(
                        ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(pool)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(timeout)
                        .setConnectionRequestTimeout(timeout)
                        .build())
                .build();
    }

    @Bean
    ClientHttpRequestFactory requestFactory(CloseableHttpClient upstreamClient) {
        return new HttpComponentsClientHttpRequestFactory(upstreamClient);
    }

    @Bean
    RouterFunction<ServerResponse> identityRoutes(GatewayProperties settings) {
        var routes = route("identity-auth");
        GatewayFilter.PATHS.forEach(path -> routes.POST(path, http()));
        return routes.before(uri(settings.identityUrl().toString())).build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    RequestHttpHeadersFilter trustedHeaders() {
        return (headers, request) -> {
            var clean = new HttpHeaders();
            for (String name : List.of("Content-Type", "Accept", "Authorization", "Idempotency-Key")) {
                var values = headers.get(name);
                if (values != null)
                    clean.put(name, List.copyOf(values));
            }
            clean.set("X-Request-Id", request.servletRequest().getAttribute("request_id").toString());
            clean.set("X-Forwarded-For", request.servletRequest().getRemoteAddr());
            return clean;
        };
    }
}
