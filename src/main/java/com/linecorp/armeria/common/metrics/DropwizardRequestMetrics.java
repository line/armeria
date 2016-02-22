package com.linecorp.armeria.common.metrics;

import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.Timer;

/**
 * {@link Metric}s for a single request. Used by
 * {@link com.linecorp.armeria.client.metrics.MetricCollectingClient} and
 * {@link com.linecorp.armeria.server.metrics.MetricCollectingService} for tracking
 * request metrics. For internal use only.
 */
public final class DropwizardRequestMetrics {

    private final String name;
    private final Timer timer;
    private final Meter successes;
    private final Meter failures;
    private final Meter requestBytes;
    private final Meter responseBytes;

    public DropwizardRequestMetrics(String name, Timer timer, Meter successes, Meter failures,
                                    Meter requestBytes, Meter responseBytes) {
        this.name = name;
        this.timer = timer;
        this.successes = successes;
        this.failures = failures;
        this.requestBytes = requestBytes;
        this.responseBytes = responseBytes;
    }

    public Timer.Context time() {
        return timer.time();
    }

    public void updateTime(long durationNanos) {
        timer.update(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void markSuccess() {
        successes.mark();
    }

    public void markFailure() {
        failures.mark();
    }

    public void requestBytes(int requestBytes) {
        this.requestBytes.mark(requestBytes);
    }

    public void responseBytes(int responseBytes) {
        this.responseBytes.mark(responseBytes);
    }

    @Override
    public String toString() {
        return "<DropwizardRequestMetrics for: " + name + '\n' +
               "  requests: " + timer.getCount() + '\n' +
               "  successes: " + successes.getCount() + '\n' +
               "  failures: " + failures.getCount() + '\n' +
               "  requestBytes: " + requestBytes.getCount() + '\n' +
               "  responseBytes: " + responseBytes.getCount() + "\n>";
    }
}
