package io.github.returnaction.fitness.gateway.forwarding;

import io.github.returnaction.fitness.gateway.timeout.HttpDeadlineScope;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.io.UncheckedIOException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class DeadlineRequestFactory extends HttpComponentsClientHttpRequestFactory {

    public DeadlineRequestFactory(CloseableHttpClient client) {
        super(client);
    }

    @Override
    protected ClassicHttpRequest createHttpUriRequest(HttpMethod method, URI uri) {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        var scope = (HttpDeadlineScope) attributes.getRequest().getAttribute(HttpDeadlineScope.ATTRIBUTE);
        if (scope == null) {
            throw new IllegalStateException("HTTP deadline scope is required before proxying");
        }
        var outgoing = new HttpUriRequestBase(method.name(), uri);
        try {
            scope.register(outgoing);
        } catch (SocketTimeoutException exception) {
            throw new UncheckedIOException(exception);
        }
        return outgoing;
    }
}
