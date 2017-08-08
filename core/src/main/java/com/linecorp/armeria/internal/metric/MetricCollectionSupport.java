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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.metric.DefaultHistogram;
import com.linecorp.armeria.common.metric.Gauge;
import com.linecorp.armeria.common.metric.LongAdderGauge;
import com.linecorp.armeria.common.metric.MetricKey;
import com.linecorp.armeria.common.metric.MetricKeyFunction;
import com.linecorp.armeria.common.metric.MetricUnit;
import com.linecorp.armeria.common.metric.Metrics;
import com.linecorp.armeria.common.metric.RequestMetrics;
import com.linecorp.armeria.common.metric.SupplierGauge;

import io.netty.util.AttributeKey;

/**
 * (Internal use only) Collects the metric data and stores it into the {@link RequestMetrics}.
 */
public final class MetricCollectionSupport {

    private static final AttributeKey<MutableRequestMetrics> ATTR_REQUEST_METRICS =
            AttributeKey.valueOf(MetricCollectionSupport.class, "REQUEST_METRICS");

    private static final MutableRequestMetrics PLACEHOLDER = new RequestMetricsPlaceholder();

    public static void setup(RequestContext ctx, MetricKeyFunction metricKeyFunction) {
        if (ctx.hasAttr(ATTR_REQUEST_METRICS)) {
            return;
        }

        // Set the attribute to the placeholder so that calling this method again has no effect.
        // The attribute will be set to the real one later in onRequestStart()
        ctx.attr(ATTR_REQUEST_METRICS).set(PLACEHOLDER);

        ctx.log().addListener(log -> onRequestStart(log, metricKeyFunction),
                              RequestLogAvailability.REQUEST_HEADERS,
                              RequestLogAvailability.REQUEST_CONTENT);
        ctx.log().addListener(MetricCollectionSupport::onRequestEnd,
                              RequestLogAvailability.REQUEST_END);
        ctx.log().addListener(MetricCollectionSupport::onResponse,
                              RequestLogAvailability.COMPLETE);
    }

    private static void onRequestStart(RequestLog log, MetricKeyFunction metricKeyFunction) {
        final MetricKey key = metricKeyFunction.apply(log);
        final RequestContext ctx = log.context();
        final Metrics metrics = ctx.metrics();
        final MutableRequestMetrics requestMetrics = metrics.group(
                key, MutableRequestMetrics.class, DefaultRequestMetrics::new);

        ctx.attr(ATTR_REQUEST_METRICS).set(requestMetrics);
        requestMetrics.active().inc();
    }

    private static void onRequestEnd(RequestLog log) {
        final MutableRequestMetrics metrics = requestMetrics(log);
        metrics.requestDuration().update(log.requestDurationNanos());
        metrics.requestLength().update(log.requestLength());
        if (log.requestCause() != null) {
            metrics.failure().inc();
            metrics.active().dec();
        }
    }

    private static void onResponse(RequestLog log) {
        if (log.requestCause() != null) {
            return;
        }

        final MutableRequestMetrics metrics = requestMetrics(log);
        metrics.responseDuration().update(log.responseDurationNanos());
        metrics.responseLength().update(log.responseLength());
        metrics.totalDuration().update(log.totalDurationNanos());

        if (isSuccess(log)) {
            metrics.success().inc();
        } else {
            metrics.failure().inc();
        }

        metrics.active().dec();
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

    private static MutableRequestMetrics requestMetrics(RequestLog log) {
        return log.context().attr(ATTR_REQUEST_METRICS).get();
    }

    private MetricCollectionSupport() {}

    private interface MutableRequestMetrics extends RequestMetrics {

        @Override
        LongAdderGauge active();

        @Override
        LongAdderGauge success();

        @Override
        LongAdderGauge failure();

        @Override
        DefaultHistogram requestDuration();

        @Override
        DefaultHistogram requestLength();

        @Override
        DefaultHistogram responseDuration();

        @Override
        DefaultHistogram responseLength();

        @Override
        DefaultHistogram totalDuration();
    }

    private static final class DefaultRequestMetrics implements MutableRequestMetrics {

        private final MetricKey key;
        private final LongAdderGauge active;
        private final Gauge total;
        private final LongAdderGauge success;
        private final LongAdderGauge failure;
        private final DefaultHistogram requestDuration;
        private final DefaultHistogram requestLength;
        private final DefaultHistogram responseDuration;
        private final DefaultHistogram responseLength;
        private final DefaultHistogram totalDuration;

        DefaultRequestMetrics(Metrics parent, MetricKey key) {
            this.key = requireNonNull(key, "key");

            active = parent.register(new LongAdderGauge(
                    key().append("active"), MetricUnit.COUNT,
                    "the number of requests currently being handled", true));
            success = parent.register(new LongAdderGauge(
                    key().append("success"), MetricUnit.COUNT_CUMULATIVE,
                    "the number of successfully handled requests"));
            failure = parent.register(new LongAdderGauge(
                    key().append("failure"), MetricUnit.COUNT_CUMULATIVE,
                    "the number of non-successfully handled requests"));
            requestDuration = parent.register(new DefaultHistogram(
                    key().append("requestDuration"), MetricUnit.NANOSECONDS,
                    "the amount of time took to produce/consume a request"));
            requestLength = parent.register(new DefaultHistogram(
                    key().append("requestLength"), MetricUnit.BYTES,
                    "the number of bytes in a request"));
            responseDuration = parent.register(new DefaultHistogram(
                    key().append("responseDuration"), MetricUnit.NANOSECONDS,
                    "the amount of time took to produce/consume a response"));
            responseLength = parent.register(new DefaultHistogram(
                    key().append("responseLength"), MetricUnit.BYTES,
                    "the number of bytes in a response"));
            totalDuration = parent.register(new DefaultHistogram(
                    key().append("totalDuration"), MetricUnit.NANOSECONDS,
                    "the amount of time took to handle a request"));

            total = parent.register(new SupplierGauge(
                    key().append("total"), MetricUnit.COUNT_CUMULATIVE,
                    "the number of handled requests",
                    () -> success.value() + failure.value()));
        }

        @Override
        public MetricKey key() {
            return key;
        }

        @Override
        public LongAdderGauge active() {
            return active;
        }

        @Override
        public Gauge total() {
            return total;
        }

        @Override
        public LongAdderGauge success() {
            return success;
        }

        @Override
        public LongAdderGauge failure() {
            return failure;
        }

        @Override
        public DefaultHistogram requestDuration() {
            return requestDuration;
        }

        @Override
        public DefaultHistogram requestLength() {
            return requestLength;
        }

        @Override
        public DefaultHistogram responseDuration() {
            return responseDuration;
        }

        @Override
        public DefaultHistogram responseLength() {
            return responseLength;
        }

        @Override
        public DefaultHistogram totalDuration() {
            return totalDuration;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("key", key)
                              .add("active", active().value())
                              .add("total", total().value())
                              .add("success", success().value())
                              .add("failure", failure().value())
                              .add("requestDuration", requestDuration())
                              .add("requestLength", requestLength())
                              .add("responseDuration", responseDuration())
                              .add("responseLength", responseLength())
                              .add("totalDuration", totalDuration())
                              .toString();
        }
    }

    private static final class RequestMetricsPlaceholder implements MutableRequestMetrics {
        @Override
        public MetricKey key() {
            throw new IllegalStateException();
        }

        @Override
        public LongAdderGauge active() {
            throw new IllegalStateException();
        }

        @Override
        public Gauge total() {
            throw new IllegalStateException();
        }

        @Override
        public LongAdderGauge success() {
            throw new IllegalStateException();
        }

        @Override
        public LongAdderGauge failure() {
            throw new IllegalStateException();
        }

        @Override
        public DefaultHistogram requestDuration() {
            throw new IllegalStateException();
        }

        @Override
        public DefaultHistogram requestLength() {
            throw new IllegalStateException();
        }

        @Override
        public DefaultHistogram responseDuration() {
            throw new IllegalStateException();
        }

        @Override
        public DefaultHistogram responseLength() {
            throw new IllegalStateException();
        }

        @Override
        public DefaultHistogram totalDuration() {
            throw new IllegalStateException();
        }
    }
}
