package com.linecorp.armeria.common.metrics;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.server.ServiceCodec;

/**
 * {@link MetricConsumer} that accepts metric data from
 * {@link com.linecorp.armeria.client.metrics.MetricCollectingClient} or
 * {@link com.linecorp.armeria.server.metrics.MetricCollectingService} and stores it
 * into the {@link MetricRegistry}. Only for internal use.
 */
public class DropwizardMetricConsumer implements MetricConsumer {

    private final String prefix;
    private final String serviceName;
    private final MetricRegistry metricRegistry;
    private final Map<String, DropwizardRequestMetrics> methodRequestMetrics;

    /**
     * Creates a new instance that decorates the specified {@link ServiceCodec}.
     */
    public DropwizardMetricConsumer(String prefix, String serviceName, MetricRegistry metricRegistry) {
        this.prefix = prefix;
        this.serviceName = serviceName;
        this.metricRegistry = metricRegistry;
        methodRequestMetrics = new ConcurrentHashMap<>();
    }

    @Override
    public void invocationComplete(Scheme scheme, int code, long processTimeNanos, int requestSize,
                                   int responseSize, String hostname, String path, Optional<String> method) {
        String metricName = MetricRegistry.name(prefix, serviceName, method.orElse("__unknown__"));
        DropwizardRequestMetrics metrics = getRequestMetrics(metricName);
        metrics.updateTime(processTimeNanos);
        if (code < 400) {
            metrics.markSuccess();
        } else {
            metrics.markFailure();
        }
        metrics.requestBytes(requestSize);
        metrics.responseBytes(responseSize);
    }

    private DropwizardRequestMetrics getRequestMetrics(String methodLoggedName) {
        return methodRequestMetrics.computeIfAbsent(
                methodLoggedName,
                m -> new DropwizardRequestMetrics(
                        m,
                        metricRegistry.timer(MetricRegistry.name(m, "requests")),
                        metricRegistry.meter(MetricRegistry.name(m, "successes")),
                        metricRegistry.meter(MetricRegistry.name(m, "failures")),
                        metricRegistry.meter(MetricRegistry.name(m, "requestBytes")),
                        metricRegistry.meter(MetricRegistry.name(m, "responseBytes"))));
    }
}
