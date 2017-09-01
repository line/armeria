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

import static com.linecorp.armeria.common.metric.MoreMeters.summaryWithDefaultQuantiles;
import static com.linecorp.armeria.common.metric.MoreMeters.timerWithDefaultQuantiles;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.metric.MeterId;
import com.linecorp.armeria.common.metric.MeterIdFunction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.util.AttributeKey;

/**
 * Collects the metric data and stores it into the {@link MeterRegistry}.
 */
public final class RequestMetricSupport {

    private static final AttributeKey<RequestMetrics> ATTR_REQUEST_METRICS =
            AttributeKey.valueOf(RequestMetricSupport.class, "REQUEST_METRICS");

    private static final RequestMetrics PLACEHOLDER = new RequestMetricsPlaceholder();

    public static void setup(RequestContext ctx, MeterIdFunction meterIdFunction) {
        if (ctx.hasAttr(ATTR_REQUEST_METRICS)) {
            return;
        }

        // Set the attribute to the placeholder so that calling this method again has no effect.
        // The attribute will be set to the real one later in onRequestStart()
        ctx.attr(ATTR_REQUEST_METRICS).set(PLACEHOLDER);

        ctx.log().addListener(log -> onRequestStart(log, meterIdFunction),
                              RequestLogAvailability.REQUEST_HEADERS,
                              RequestLogAvailability.REQUEST_CONTENT);
        ctx.log().addListener(RequestMetricSupport::onRequestEnd,
                              RequestLogAvailability.REQUEST_END);
        ctx.log().addListener(RequestMetricSupport::onResponse,
                              RequestLogAvailability.COMPLETE);
    }

    private static void onRequestStart(RequestLog log, MeterIdFunction meterIdFunction) {
        final RequestContext ctx = log.context();
        final MeterRegistry registry = ctx.meterRegistry();
        final MeterId id = meterIdFunction.apply(registry, log);
        final RequestMetrics requestMetrics =  MicrometerUtil.register(
                registry, id, RequestMetrics.class, DefaultRequestMetrics::new);

        ctx.attr(ATTR_REQUEST_METRICS).set(requestMetrics);
        requestMetrics.active().incrementAndGet();
    }

    private static void onRequestEnd(RequestLog log) {
        final RequestMetrics metrics = requestMetrics(log);
        metrics.requestDuration().record(log.requestDurationNanos(), TimeUnit.NANOSECONDS);
        metrics.requestLength().record(log.requestLength());
        if (log.requestCause() != null) {
            metrics.failure().increment();
            metrics.active().decrementAndGet();
        }
    }

    private static void onResponse(RequestLog log) {
        if (log.requestCause() != null) {
            return;
        }

        final RequestMetrics metrics = requestMetrics(log);
        metrics.responseDuration().record(log.responseDurationNanos(), TimeUnit.NANOSECONDS);
        metrics.responseLength().record(log.responseLength());
        metrics.totalDuration().record(log.totalDurationNanos(), TimeUnit.NANOSECONDS);

        if (isSuccess(log)) {
            metrics.success().increment();
        } else {
            metrics.failure().increment();
        }

        metrics.active().decrementAndGet();
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

    private static RequestMetrics requestMetrics(RequestLog log) {
        return log.context().attr(ATTR_REQUEST_METRICS).get();
    }

    private RequestMetricSupport() {}

    private interface RequestMetrics {
        AtomicInteger active();

        Counter success();

        Counter failure();

        Timer requestDuration();

        DistributionSummary requestLength();

        Timer responseDuration();

        DistributionSummary responseLength();

        Timer totalDuration();
    }

    private static final class DefaultRequestMetrics implements RequestMetrics {

        private final AtomicInteger active;
        private final Counter success;
        private final Counter failure;
        private final Timer requestDuration;
        private final DistributionSummary requestLength;
        private final Timer responseDuration;
        private final DistributionSummary responseLength;
        private final Timer totalDuration;

        DefaultRequestMetrics(MeterRegistry parent, MeterId id) {
            active = parent.gauge(id.name("activeRequests"), id.tags(),
                                  new AtomicInteger(), AtomicInteger::get);
            final String requests = id.name("requests");
            success = parent.counter(requests, id.tags("result", "success"));
            failure = parent.counter(requests, id.tags("result", "failure"));

            requestDuration = timerWithDefaultQuantiles(parent, id.append("requestDuration"));
            requestLength = summaryWithDefaultQuantiles(parent, id.append("requestLength"));
            responseDuration = timerWithDefaultQuantiles(parent, id.append("responseDuration"));
            responseLength = summaryWithDefaultQuantiles(parent, id.append("responseLength"));
            totalDuration = timerWithDefaultQuantiles(parent, id.append("totalDuration"));
        }

        @Override
        public AtomicInteger active() {
            return active;
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

    private static final class RequestMetricsPlaceholder implements RequestMetrics {
        @Override
        public AtomicInteger active() {
            throw new IllegalStateException();
        }

        @Override
        public Counter success() {
            throw new IllegalStateException();
        }

        @Override
        public Counter failure() {
            throw new IllegalStateException();
        }

        @Override
        public Timer requestDuration() {
            throw new IllegalStateException();
        }

        @Override
        public DistributionSummary requestLength() {
            throw new IllegalStateException();
        }

        @Override
        public Timer responseDuration() {
            throw new IllegalStateException();
        }

        @Override
        public DistributionSummary responseLength() {
            throw new IllegalStateException();
        }

        @Override
        public Timer totalDuration() {
            throw new IllegalStateException();
        }
    }
}
