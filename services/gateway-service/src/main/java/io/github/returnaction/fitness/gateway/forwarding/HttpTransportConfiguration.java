package io.github.returnaction.fitness.gateway.forwarding;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import io.github.returnaction.fitness.gateway.timeout.DeadlineHttp11Protocol;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

@Configuration(proxyBeanMethods = false)
public class HttpTransportConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> deadlineConnector() {
        return factory -> factory.setProtocol(DeadlineHttp11Protocol.class.getName());
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledThreadPoolExecutor httpDeadlineScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("gateway-http-deadline").factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    CloseableHttpClient upstreamHttpClient(PoolingHttpClientConnectionManager upstreamConnections) {
        return HttpClients.custom()
                .setConnectionManager(upstreamConnections)
                .setConnectionManagerShared(true)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager upstreamConnections() {
        return new PoolingHttpClientConnectionManager();
    }

    @Bean
    ClientHttpRequestFactory upstreamRequestFactory(CloseableHttpClient upstreamHttpClient) {
        return new DeadlineRequestFactory(upstreamHttpClient);
    }
}
