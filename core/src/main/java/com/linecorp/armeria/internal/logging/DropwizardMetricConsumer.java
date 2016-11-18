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
import java.util.function.BiFunction;

import com.codahale.metrics.MetricRegistry;

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
    private final BiFunction<RequestContext, RequestLog, String> metricNameFunc;
    private final Map<String, DropwizardRequestMetrics> methodRequestMetrics;

    /**
     * Creates a new instance.
     */
    public DropwizardMetricConsumer(
            MetricRegistry metricRegistry, BiFunction<RequestContext, RequestLog, String> metricNameFunc) {

        this.metricRegistry = requireNonNull(metricRegistry, "metricRegistry");
        this.metricNameFunc = requireNonNull(metricNameFunc, "metricNameFunc");
        methodRequestMetrics = new ConcurrentHashMap<>();
    }

    @Override
    public void onRequest(RequestContext ctx, RequestLog req) {
        final DropwizardRequestMetrics metrics = getRequestMetrics(ctx, req);
        if (req.cause() == null) {
            metrics.markStart();
        } else {
            metrics.markFailure();
        }
    }

    @Override
    public void onResponse(RequestContext ctx, ResponseLog res) {
        final RequestLog req = res.request();
        final DropwizardRequestMetrics metrics = getRequestMetrics(ctx, req);
        metrics.updateTime(res.responseTimeNanos());
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

    private DropwizardRequestMetrics getRequestMetrics(RequestContext ctx, RequestLog req) {
        final String metricName = metricNameFunc.apply(ctx, req);
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
