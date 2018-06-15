/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.metric;

import static com.linecorp.armeria.common.metric.MoreMeters.newDistributionSummary;
import static com.linecorp.armeria.common.metric.MoreMeters.newTimer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.util.AttributeKey;

/**
 * Collects the metric data and stores it into the {@link MeterRegistry}.
 */
public final class RequestMetricSupport {

    // A variable to make sure setup method is not called twice.
    private static final AttributeKey<Boolean> ATTR_REQUEST_METRICS_SET =
            AttributeKey.valueOf(Boolean.class, "REQUEST_METRICS_SET");

    public static void setup(RequestContext ctx, MeterIdPrefixFunction meterIdPrefixFunction) {
        if (ctx.hasAttr(ATTR_REQUEST_METRICS_SET)) {
            return;
        }
        ctx.attr(ATTR_REQUEST_METRICS_SET).set(true);

        ctx.log().addListener(log -> onRequest(log, meterIdPrefixFunction),
                              RequestLogAvailability.REQUEST_HEADERS,
                              RequestLogAvailability.REQUEST_CONTENT);
    }

    private static void onRequest(RequestLog log, MeterIdPrefixFunction meterIdPrefixFunction) {
        final RequestContext ctx = log.context();
        final MeterRegistry registry = ctx.meterRegistry();
        final MeterIdPrefix activeRequestsId = meterIdPrefixFunction.activeRequestPrefix(registry, log)
                                                                    .append("activeRequests");

        final ActiveRequestMetrics activeRequestMetrics = MicrometerUtil.register(
                registry, activeRequestsId, ActiveRequestMetrics.class,
                (reg, prefix) ->
                        reg.gauge(prefix.name(), prefix.tags(),
                                  new ActiveRequestMetrics(), ActiveRequestMetrics::doubleValue));
        activeRequestMetrics.increment();
        ctx.log().addListener(requestLog -> onResponse(requestLog, meterIdPrefixFunction, activeRequestMetrics),
                              RequestLogAvailability.COMPLETE);
    }

    private static void onResponse(RequestLog log, MeterIdPrefixFunction meterIdPrefixFunction,
                                   ActiveRequestMetrics activeRequestMetrics) {
        final RequestContext ctx = log.context();
        final MeterRegistry registry = ctx.meterRegistry();
        final MeterIdPrefix idPrefix = meterIdPrefixFunction.apply(registry, log);
        final RequestMetrics metrics = MicrometerUtil.register(
                registry, idPrefix, RequestMetrics.class, DefaultRequestMetrics::new);

        if (log.requestCause() != null) {
            metrics.failure().increment();
            return;
        }

        metrics.requestDuration().record(log.requestDurationNanos(), TimeUnit.NANOSECONDS);
        metrics.requestLength().record(log.requestLength());
        metrics.responseDuration().record(log.responseDurationNanos(), TimeUnit.NANOSECONDS);
        metrics.responseLength().record(log.responseLength());
        metrics.totalDuration().record(log.totalDurationNanos(), TimeUnit.NANOSECONDS);

        if (isSuccess(log)) {
            metrics.success().increment();
        } else {
            metrics.failure().increment();
        }

        activeRequestMetrics.decrement();
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

    private RequestMetricSupport() {}

    // metrics that only needed to be called when a request completed
    private interface RequestMetrics {
        Counter success();

        Counter failure();

        Timer requestDuration();

        DistributionSummary requestLength();

        Timer responseDuration();

        DistributionSummary responseLength();

        Timer totalDuration();
    }

    private static final class ActiveRequestMetrics extends LongAdder {}

    private static final class DefaultRequestMetrics implements RequestMetrics {

        private final Counter success;
        private final Counter failure;
        private final Timer requestDuration;
        private final DistributionSummary requestLength;
        private final Timer responseDuration;
        private final DistributionSummary responseLength;
        private final Timer totalDuration;

        DefaultRequestMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
            final String requests = idPrefix.name("requests");
            success = parent.counter(requests, idPrefix.tags("result", "success"));
            failure = parent.counter(requests, idPrefix.tags("result", "failure"));

            requestDuration = newTimer(
                    parent, idPrefix.name("requestDuration"), idPrefix.tags());
            requestLength = newDistributionSummary(
                    parent, idPrefix.name("requestLength"), idPrefix.tags());
            responseDuration = newTimer(
                    parent, idPrefix.name("responseDuration"), idPrefix.tags());
            responseLength = newDistributionSummary(
                    parent, idPrefix.name("responseLength"), idPrefix.tags());
            totalDuration = newTimer(
                    parent, idPrefix.name("totalDuration"), idPrefix.tags());
        }

        @Override
        public Counter success() {
            return success;
        }

        @Override
        public Counter failure() {
            return failure;
        }

        @Override
        public Timer requestDuration() {
            return requestDuration;
        }

        @Override
        public DistributionSummary requestLength() {
            return requestLength;
        }

        @Override
        public Timer responseDuration() {
            return responseDuration;
        }

        @Override
        public DistributionSummary responseLength() {
            return responseLength;
        }

        @Override
        public Timer totalDuration() {
            return totalDuration;
        }
    }
}
