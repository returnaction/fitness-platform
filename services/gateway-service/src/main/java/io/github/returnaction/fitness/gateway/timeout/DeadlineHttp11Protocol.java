package io.github.returnaction.fitness.gateway.timeout;

import java.io.IOException;
import org.apache.coyote.Processor;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Request;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http11.Http11Processor;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * Exposes the active Tomcat connection only to its request-processing thread.
 */
public final class DeadlineHttp11Protocol extends Http11NioProtocol {

    private static final ThreadLocal<SocketWrapperBase<?>> CONNECTION = new ThreadLocal<>();
    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();

    public static void stopReadingRequest() {
        var request = REQUEST.get();
        if (request != null) {
            request.action(ActionCode.DISABLE_SWALLOW_INPUT, null);
        }
    }

    static boolean responseStarted() {
        return REQUEST.get().getResponse().isCommitted();
    }

    static void closeConnection() {
        connection().close();
    }

    static SocketWrapperBase<?> connection() {
        var connection = CONNECTION.get();
        if (connection == null) {
            throw new IllegalStateException("Deadline-aware HTTP/1.1 connector is required");
        }
        return connection;
    }

    @Override
    protected Processor createProcessor() {
        return new Http11Processor(this, getAdapter()) {

            @Override
            public SocketState service(SocketWrapperBase<?> socket) throws IOException {
                CONNECTION.set(socket);
                REQUEST.set(getRequest());
                try {
                    return super.service(socket);
                } finally {
                    CONNECTION.remove();
                    REQUEST.remove();
                }
            }
        };
    }
}
