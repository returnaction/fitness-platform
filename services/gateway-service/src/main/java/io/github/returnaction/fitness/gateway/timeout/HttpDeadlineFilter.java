package io.github.returnaction.fitness.gateway.timeout;

import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-1)
@RequiredArgsConstructor
public final class HttpDeadlineFilter extends OncePerRequestFilter {

    private final RouteAccessPolicy policy;
    private final ScheduledThreadPoolExecutor httpDeadlineScheduler;
    private final GatewayErrorWriter errors;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !policy.publicRequests().matches(request) && !policy.protectedRequests().matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        var deadline = RequestDeadlineFilter.requireDeadline(request);
        HttpDeadlineScope scope = null;
        try {
            scope = new HttpDeadlineScope(deadline, httpDeadlineScheduler);
            request.setAttribute(HttpDeadlineScope.ATTRIBUTE, scope);
            var input = new DeadlineInputStream(request.getInputStream(), scope);
            var output = new DeadlineOutputStream(response.getOutputStream(), scope);
            chain.doFilter(new HttpServletRequestWrapper(request) {

                @Override
                @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Servlet must reuse the same request stream")
                public ServletInputStream getInputStream() {
                    return input;
                }
            }, new HttpServletResponseWrapper(response) {

                @Override
                @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Servlet must reuse the same response stream")
                public ServletOutputStream getOutputStream() {
                    return output;
                }
            });
            output.flush();
        } catch (IOException | ServletException | RuntimeException failure) {
            boolean timeout = (scope != null && scope.timedOut()) || causedBy(failure, SocketTimeoutException.class);
            if (scope != null) {
                scope.close();
            }
            if (DeadlineHttp11Protocol.responseStarted()
                    || (!timeout && causedBy(failure, ClientAbortException.class))) {
                DeadlineHttp11Protocol.closeConnection();
                return;
            }
            if (response.isCommitted()) {
                throw failure;
            }
            if (timeout || causedBy(failure, SocketTimeoutException.class)) {
                errors.write(response, 504, GatewayErrorCode.UPSTREAM_TIMEOUT, RequestIdResolver.getOrCreate(request));
            } else if (causedBy(failure, ResourceAccessException.class)) {
                errors.write(response, 502, GatewayErrorCode.UPSTREAM_UNAVAILABLE,
                        RequestIdResolver.getOrCreate(request));
            } else {
                throw failure;
            }
        } finally {
            if (scope != null) {
                scope.close();
            }
            request.removeAttribute(HttpDeadlineScope.ATTRIBUTE);
        }
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
