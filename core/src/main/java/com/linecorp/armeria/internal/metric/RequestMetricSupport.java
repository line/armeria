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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.RequestTimeoutException;

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

    public static void setup(RequestContext ctx, MeterIdPrefixFunction meterIdPrefixFunction, boolean server) {
        if (ctx.hasAttr(ATTR_REQUEST_METRICS_SET)) {
            return;
        }
        ctx.attr(ATTR_REQUEST_METRICS_SET).set(true);

        ctx.log().addListener(log -> onRequest(log, meterIdPrefixFunction, server),
                              RequestLogAvailability.REQUEST_HEADERS,
                              RequestLogAvailability.REQUEST_CONTENT);
    }

    private static void onRequest(RequestLog log, MeterIdPrefixFunction meterIdPrefixFunction, boolean server) {
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
        ctx.log().addListener(requestLog -> {
                                  onResponse(requestLog, meterIdPrefixFunction, server);
                                  activeRequestMetrics.decrement();
                              },
                              RequestLogAvailability.COMPLETE);
    }

    private static void onResponse(RequestLog log, MeterIdPrefixFunction meterIdPrefixFunction,
                                   boolean server) {
        final RequestContext ctx = log.context();
        final MeterRegistry registry = ctx.meterRegistry();
        final MeterIdPrefix idPrefix = meterIdPrefixFunction.apply(registry, log);

        if (server) {
            final ServiceRequestMetrics metrics = MicrometerUtil.register(registry, idPrefix,
                                                                          ServiceRequestMetrics.class,
                                                                          DefaultServiceRequestMetrics::new);
            updateMetrics(log, metrics);
            if (log.responseCause() instanceof RequestTimeoutException) {
                metrics.requestTimeouts().increment();
            }
            return;
        }

        final ClientRequestMetrics metrics = MicrometerUtil.register(registry, idPrefix,
                                                                     ClientRequestMetrics.class,
                                                                     DefaultClientRequestMetrics::new);
        updateMetrics(log, metrics);
        if (log.requestCause() != null) {
            if (log.requestCause() instanceof WriteTimeoutException) {
                metrics.writeTimeouts().increment();
            }
            return;
        }

        if (log.responseCause() instanceof ResponseTimeoutException) {
            metrics.responseTimeouts().increment();
        }

        final int childrenSize = log.children().size();
        if (childrenSize > 0) {
            metrics.actualRequests().increment(childrenSize);
        }
    }

    private static void updateMetrics(RequestLog log, RequestMetrics metrics) {
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

    private interface ClientRequestMetrics extends RequestMetrics {
        Counter actualRequests();

        Counter writeTimeouts();

        Counter responseTimeouts();
    }

    private interface ServiceRequestMetrics extends RequestMetrics {
        Counter requestTimeouts();
    }

    private static final class ActiveRequestMetrics extends LongAdder {}

    private abstract static class AbstractRequestMetrics implements RequestMetrics {

        private final Counter success;
        private final Counter failure;
        private final Timer requestDuration;
        private final DistributionSummary requestLength;
        private final Timer responseDuration;
        private final DistributionSummary responseLength;
        private final Timer totalDuration;

        AbstractRequestMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
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

    private static class DefaultClientRequestMetrics
            extends AbstractRequestMetrics implements ClientRequestMetrics {

        private static final AtomicReferenceFieldUpdater<DefaultClientRequestMetrics, Counter>
                actualRequestsUpdater = AtomicReferenceFieldUpdater.newUpdater(
                DefaultClientRequestMetrics.class, Counter.class, "actualRequests");

        private final MeterRegistry parent;
        private final MeterIdPrefix idPrefix;

        private final Counter writeTimeouts;
        private final Counter responseTimeouts;

        @Nullable
        private volatile Counter actualRequests;

        DefaultClientRequestMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
            super(parent, idPrefix);
            this.parent = parent;
            this.idPrefix = idPrefix;
            final String timeouts = idPrefix.name("timeouts");
            writeTimeouts = parent.counter(timeouts, idPrefix.tags("cause", "WriteTimeoutException"));
            responseTimeouts = parent.counter(timeouts, idPrefix.tags("cause", "ResponseTimeoutException"));
        }

        @Override
        public Counter actualRequests() {
            final Counter actualRequests = this.actualRequests;
            if (actualRequests != null) {
                return actualRequests;
            }

            final Counter counter = parent.counter(idPrefix.name("actualRequests"), idPrefix.tags());
            if (actualRequestsUpdater.compareAndSet(this, null, counter)) {
                return counter;
            }
            return this.actualRequests;
        }

        @Override
        public Counter writeTimeouts() {
            return writeTimeouts;
        }

        @Override
        public Counter responseTimeouts() {
            return responseTimeouts;
        }
    }

    private static class DefaultServiceRequestMetrics
            extends AbstractRequestMetrics implements ServiceRequestMetrics {

        private final Counter requestTimeouts;

        DefaultServiceRequestMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
            super(parent, idPrefix);
            requestTimeouts = parent.counter(idPrefix.name("timeouts"),
                                             idPrefix.tags("cause", "RequestTimeoutException"));
        }

        @Override
        public Counter requestTimeouts() {
            return requestTimeouts;
        }
    }
}
