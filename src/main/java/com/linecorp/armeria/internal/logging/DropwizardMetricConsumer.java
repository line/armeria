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

package com.linecorp.armeria.internal.logging;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.logging.LogCollectingClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.MessageLogConsumer;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.server.logging.LogCollectingService;

/**
 * (Internal use only) {@link MessageLogConsumer} that accepts metric data from {@link LogCollectingClient} or
 * {@link LogCollectingService} and stores it into the {@link MetricRegistry}.
 */
public final class DropwizardMetricConsumer implements MessageLogConsumer {

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
    public void onRequest(RequestContext ctx, RequestLog req) {
        final String metricName = MetricRegistry.name(metricNamePrefix, method(req));
        final DropwizardRequestMetrics metrics = getRequestMetrics(metricName);
        if (req.cause() == null) {
            metrics.markStart();
        } else {
            metrics.markFailure();
        }
    }

    @Override
    public void onResponse(RequestContext ctx, ResponseLog res) {
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

        return !res.hasAttr(ResponseLog.RPC_RESPONSE) ||
               res.attr(ResponseLog.RPC_RESPONSE).get().getCause() == null;
    }

    private static String method(RequestLog log) {
        if (log.hasAttr(RequestLog.RPC_REQUEST)) {
            return log.attr(RequestLog.RPC_REQUEST).get().method();
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
