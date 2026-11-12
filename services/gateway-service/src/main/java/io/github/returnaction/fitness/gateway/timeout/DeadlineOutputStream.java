package io.github.returnaction.fitness.gateway.timeout;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;

final class DeadlineOutputStream extends ServletOutputStream {

    private final ServletOutputStream delegate;
    private final HttpDeadlineScope scope;

    DeadlineOutputStream(ServletOutputStream delegate, HttpDeadlineScope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    @Override
    public void write(int value) throws IOException {
        scope.beginWrite();
        try {
            delegate.write(value);
        } finally {
            scope.endWrite();
        }
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        scope.beginWrite();
        try {
            delegate.write(buffer, offset, length);
        } finally {
            scope.endWrite();
        }
    }

    @Override
    public void flush() throws IOException {
        scope.beginWrite();
        try {
            delegate.flush();
        } finally {
            scope.endWrite();
        }
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void setWriteListener(WriteListener listener) {
        delegate.setWriteListener(listener);
    }
}
