/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.metric;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * (Internal use only) Collects the metric data and stores it into the {@link MetricRegistry}.
 */
public final class DropwizardMetricCollector {

    private final MetricRegistry metricRegistry;
    private final Function<RequestLog, String> metricNameFunc;
    private final Map<String, DropwizardRequestMetrics> methodRequestMetrics;

    /**
     * Creates a new instance.
     */
    public DropwizardMetricCollector(
            MetricRegistry metricRegistry, Function<RequestLog, String> metricNameFunc) {

        this.metricRegistry = requireNonNull(metricRegistry, "metricRegistry");
        this.metricNameFunc = requireNonNull(metricNameFunc, "metricNameFunc");
        methodRequestMetrics = new ConcurrentHashMap<>();
    }

    public void onRequestStart(RequestLog log) {
        final DropwizardRequestMetrics metrics = getRequestMetrics(log);
        metrics.markStart();
    }

    public void onRequestEnd(RequestLog log) {
        final DropwizardRequestMetrics metrics = getRequestMetrics(log);
        metrics.requestBytes(log.requestLength());
        if (log.requestCause() != null) {
            metrics.markFailure();
            metrics.markComplete();
        }
    }

    public void onResponse(RequestLog log) {
        if (log.requestCause() != null) {
            return;
        }

        final DropwizardRequestMetrics metrics = getRequestMetrics(log);
        metrics.updateTime(log.totalDurationNanos());
        metrics.responseBytes(log.responseLength());

        if (isSuccess(log)) {
            metrics.markSuccess();
        } else {
            metrics.markFailure();
        }

        metrics.markComplete();
    }

    private static boolean isSuccess(RequestLog log) {
        if (log.responseCause() != null) {
            return false;
        }

        final int statusCode = log.statusCode();
        if (statusCode < 100 || statusCode >= 400) {
            return false;
        }

        final Object responseContent = log.responseContent();
        if (responseContent instanceof RpcResponse) {
            return !((RpcResponse) responseContent).isCompletedExceptionally();
        }

        return true;
    }

    private DropwizardRequestMetrics getRequestMetrics(RequestLog log) {
        final String metricName = metricNameFunc.apply(log);
        return methodRequestMetrics.computeIfAbsent(
                metricName,
                name -> new DropwizardRequestMetrics(
                        name,
                        metricRegistry.timer(MetricRegistry.name(name, "requests")),
                        metricRegistry.meter(MetricRegistry.name(name, "successes")),
                        metricRegistry.meter(MetricRegistry.name(name, "failures")),
                        metricRegistry.counter(MetricRegistry.name(name, "activeRequests")),
                        metricRegistry.meter(MetricRegistry.name(name, "requestBytes")),
                        metricRegistry.meter(MetricRegistry.name(name, "responseBytes"))));
    }
}
