package io.github.returnaction.fitness.gateway.timeout;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;

final class DeadlineInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final HttpDeadlineScope scope;

    DeadlineInputStream(ServletInputStream delegate, HttpDeadlineScope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    @Override
    public int read() throws IOException {
        scope.beforeRead();
        return delegate.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        scope.beforeRead();
        return delegate.read(buffer, offset, length);
    }

    @Override
    public boolean isFinished() {
        return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
        delegate.setReadListener(listener);
    }
}
