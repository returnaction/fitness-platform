package io.github.returnaction.fitness.gateway.timeout;

import io.grpc.Deadline;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public record RequestDeadline(Deadline grpcDeadline) {

    public static RequestDeadline start(Duration timeout) {
        return new RequestDeadline(Deadline.after(timeout.toNanos(), TimeUnit.NANOSECONDS));
    }

    public boolean expired() {
        return grpcDeadline.isExpired();
    }

    public long remainingNanos() {
        return Math.max(0, grpcDeadline.timeRemaining(TimeUnit.NANOSECONDS));
    }
}
