package io.github.returnaction.fitness.gateway;

import java.util.ArrayList;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.grpc.Deadline;
import io.github.returnaction.fitness.gateway.configuration.properties.RequestTimeoutProperties;
import io.github.returnaction.fitness.gateway.request.RequestIdFilter;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadline;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadlineFilter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestContextTest {

    @Test
    void reusesContextAcrossDispatchesAndCleansMdcOnFailure() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "untrusted");
        var response = new MockHttpServletResponse();
        var deadlineFilter = new RequestDeadlineFilter(new RequestTimeoutProperties(Duration.ofSeconds(3)));
        var idFilter = new RequestIdFilter();
        deadlineFilter.doFilter(request, response, (req, res) -> idFilter.doFilter(req, res, (r, s) -> {
            assertThat(MDC.get("request_id")).isEqualTo(response.getHeader("X-Request-Id"));
            assertThat(RequestDeadlineFilter.requireDeadline(request).expired()).isFalse();
        }));
        String id = response.getHeader("X-Request-Id");
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(id).isNotEqualTo("untrusted");
        var deadline = RequestDeadlineFilter.requireDeadline(request);
        for (var type : new DispatcherType[]{DispatcherType.ASYNC, DispatcherType.ERROR}) {
            request.setDispatcherType(type);
            deadlineFilter.doFilter(request, response, (req, res) -> idFilter.doFilter(req, res, (r, s) -> {
                assertThat(MDC.get("request_id")).isEqualTo(id);
                assertThat(RequestDeadlineFilter.requireDeadline(request)).isSameAs(deadline);
            }));
            assertThat(MDC.get("request_id")).isNull();
        }
        request.setDispatcherType(DispatcherType.REQUEST);
        assertThatThrownBy(() -> idFilter.doFilter(request, response, (req, res) -> {
            throw new IOException("test");
        })).isInstanceOf(IOException.class);
        assertThat(MDC.get("request_id")).isNull();
    }

    @Test
    void concurrentRequestsHaveIsolatedIds() throws Exception {
        var filter = new RequestIdFilter();
        var ids = new HashSet<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 32; i++) {
                futures.add(executor.submit(() -> {
                    var response = new MockHttpServletResponse();
                    filter.doFilter(new MockHttpServletRequest(), response, (request,
                        result) -> assertThat(MDC.get("request_id")).isEqualTo(response.getHeader("X-Request-Id")));
                    assertThat(MDC.get("request_id")).isNull();
                    return response.getHeader("X-Request-Id");
                }));
            }
            for (var future : futures) {
                ids.add(future.get(5, TimeUnit.SECONDS));
            }
        }
        assertThat(ids).hasSize(32);
    }

    @Test
    void deadlineUsesMonotonicTimeAndClampsExpiredRemainder() {
        var nanos = new AtomicLong();
        var ticker = new Deadline.Ticker() {

            @Override
            public long nanoTime() {
                return nanos.get();
            }
        };
        var deadline = new RequestDeadline(Deadline.after(3, TimeUnit.SECONDS, ticker));
        nanos.set(TimeUnit.SECONDS.toNanos(2));
        assertThat(deadline.remainingNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(1));
        nanos.set(TimeUnit.SECONDS.toNanos(4));
        assertThat(deadline.remainingNanos()).isZero();
        assertThat(deadline.expired()).isTrue();
    }
}
