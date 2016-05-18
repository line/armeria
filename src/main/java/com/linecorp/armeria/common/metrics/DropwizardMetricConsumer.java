package com.linecorp.armeria.common.metrics;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.metrics.MetricCollectingClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
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
     * Creates a new instance.
     */
    public DropwizardMetricConsumer(MetricRegistry metricRegistry, String metricNamePrefix) {
        this.metricRegistry = requireNonNull(metricRegistry, "metricRegistry");
        this.metricNamePrefix = requireNonNull(metricNamePrefix, "metricNamePrefix");
        methodRequestMetrics = new ConcurrentHashMap<>();
    }

    @Override
    public void onRequest(RequestLog req) {
        final String metricName = MetricRegistry.name(metricNamePrefix, method(req));
        final DropwizardRequestMetrics metrics = getRequestMetrics(metricName);
        if (req.cause() == null) {
            metrics.markStart();
        } else {
            metrics.markFailure();
        }
    }

    @Override
    public void onResponse(ResponseLog res) {
        final RequestLog req = res.request();
        final String metricName = MetricRegistry.name(metricNamePrefix, method(req));
        final DropwizardRequestMetrics metrics = getRequestMetrics(metricName);
        metrics.updateTime(res.endTimeNanos() - req.startTimeNanos());
        if (isSuccess(res)) {
            metrics.markSuccess();
        } else {
            metrics.markFailure();
        }
        metrics.requestBytes(req.contentLength());
        metrics.responseBytes(res.contentLength());
        if (req.cause() == null) {
            metrics.markComplete();
        }
    }

    private static boolean isSuccess(ResponseLog res) {
        if (res.cause() != null) {
            return false;
        }

        if (SessionProtocol.ofHttp().contains(res.request().scheme().sessionProtocol())) {
            if (res.statusCode() >= 400) {
                return false;
            }
        } else {
            if (res.statusCode() != 0) {
                return false;
            }
        }

        final RpcResponse rpcRes = res.attachment(RpcResponse.class);
        return rpcRes == null || rpcRes.getCause() == null;
    }

    private static String method(RequestLog log) {
        final RpcRequest rpcReq = log.attachment(RpcRequest.class);
        if (rpcReq != null) {
            return rpcReq.method();
        }

        return MoreObjects.firstNonNull(log.method(), "__unknown__");
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
