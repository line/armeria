package com.linecorp.armeria.common.metrics;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.client.metrics.MetricCollectingClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.server.ServiceCodec;
import com.linecorp.armeria.server.metrics.MetricCollectingService;

/**
 * (Internal use only) {@link MetricConsumer} that accepts metric data from {@link MetricCollectingClient} or
 * {@link MetricCollectingService} and stores it into the {@link MetricRegistry}.
 */
public class DropwizardMetricConsumer implements MetricConsumer {

    private final MetricRegistry metricRegistry;
    private final String metricNamePrefix;
    private final Map<String, DropwizardRequestMetrics> methodRequestMetrics;

    /**
     * Creates a new instance that decorates the specified {@link ServiceCodec}.
     */
    public DropwizardMetricConsumer(MetricRegistry metricRegistry, String metricNamePrefix) {
        this.metricRegistry = requireNonNull(metricRegistry, "metricRegistry");
        this.metricNamePrefix = requireNonNull(metricNamePrefix, "metricNamePrefix");
        methodRequestMetrics = new ConcurrentHashMap<>();
    }

    @Override
    public void invocationStarted(Scheme scheme, String hostname, String path, Optional<String> method) {
        final String metricName = MetricRegistry.name(metricNamePrefix, method.orElse("__unknown__"));
        final DropwizardRequestMetrics metrics = getRequestMetrics(metricName);
        metrics.markStart();
    }

    @Override
    public void invocationComplete(Scheme scheme, int code, long processTimeNanos, int requestSize,
                                   int responseSize, String hostname, String path, Optional<String> method,
                                   boolean started) {

        final String metricName = MetricRegistry.name(metricNamePrefix, method.orElse("__unknown__"));
        final DropwizardRequestMetrics metrics = getRequestMetrics(metricName);
        metrics.updateTime(processTimeNanos);
        if (code < 400) {
            metrics.markSuccess();
        } else {
            metrics.markFailure();
        }
        metrics.requestBytes(requestSize);
        metrics.responseBytes(responseSize);
        if (started) {
            metrics.markComplete();
        }
    }

    private DropwizardRequestMetrics getRequestMetrics(String methodLoggedName) {
        return methodRequestMetrics.computeIfAbsent(
                methodLoggedName,
                m -> new DropwizardRequestMetrics(
                        m,
                        metricRegistry.timer(MetricRegistry.name(m, "requests")),
                        metricRegistry.meter(MetricRegistry.name(m, "successes")),
                        metricRegistry.meter(MetricRegistry.name(m, "failures")),
                        metricRegistry.counter(MetricRegistry.name(m, "activeRequests")),
                        metricRegistry.meter(MetricRegistry.name(m, "requestBytes")),
                        metricRegistry.meter(MetricRegistry.name(m, "responseBytes"))));
    }
}
