package io.github.returnaction.fitness.gateway.timeout;

import java.net.SocketTimeoutException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * Cancellation belongs to one servlet invocation, including streaming its
 * response.
 */
public final class HttpDeadlineScope implements AutoCloseable {

    public static final String ATTRIBUTE = HttpDeadlineScope.class.getName();
    private final Thread owner = Thread.currentThread();
    private final RequestDeadline deadline;
    private final ScheduledFuture<?> future;
    private final SocketWrapperBase<?> connection = DeadlineHttp11Protocol.connection();
    private final long originalReadTimeout = connection.getReadTimeout();
    private final long originalWriteTimeout = connection.getWriteTimeout();
    private boolean writing;
    private HttpUriRequestBase outgoing;
    private boolean closed;
    private boolean timedOut;

    public HttpDeadlineScope(RequestDeadline deadline,
                             ScheduledThreadPoolExecutor scheduler) throws SocketTimeoutException {
        this.deadline = deadline;
        if (deadline.expired()) {
            throw new SocketTimeoutException("Request deadline exceeded");
        }
        future = scheduler.schedule(this::expire, deadline.remainingNanos(), TimeUnit.NANOSECONDS);
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Cancellation must target the actual executing HTTP request")
    public synchronized void register(HttpUriRequestBase request) throws SocketTimeoutException {
        if (closed || timedOut || deadline.expired()) {
            throw new SocketTimeoutException("Request deadline exceeded");
        }
        outgoing = request;
    }

    private synchronized void expire() {
        if (!closed) {
            timedOut = true;
            if (outgoing != null) {
                outgoing.cancel();
            }
            owner.interrupt();
            if (writing) {
                connection.close();
            }
        }
    }

    public synchronized boolean timedOut() {
        return timedOut;
    }

    synchronized void beforeRead() throws SocketTimeoutException {
        if (timedOut || deadline.expired()) {
            throw new SocketTimeoutException("Request deadline exceeded");
        }
        connection.setReadTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos())));
    }

    synchronized void beginWrite() throws SocketTimeoutException {
        if (closed) {
            return; // Error serialization after cancellation uses the normal container timeout.
        }
        if (timedOut || deadline.expired()) {
            throw new SocketTimeoutException("Request deadline exceeded");
        }
        connection.setWriteTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos())));
        writing = true;
    }

    synchronized void endWrite() {
        writing = false;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (outgoing != null) {
            outgoing.cancel();
        }
        outgoing = null;
        future.cancel(false);
        connection.setReadTimeout(originalReadTimeout);
        connection.setWriteTimeout(originalWriteTimeout);
        if (timedOut && Thread.currentThread() == owner) {
            Thread.interrupted();
        }
    }
}
