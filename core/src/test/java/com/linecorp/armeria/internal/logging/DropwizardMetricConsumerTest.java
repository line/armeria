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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.junit.Test;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

public class DropwizardMetricConsumerTest {

    private static final BiFunction<RequestContext, RequestLog, String> metricNameFunc =
            (ctx, req) -> "foo." + req.path() + '#' + req.method();

    @Test
    public void testMetricsForHttp() {
        final MetricRegistry metricRegistry = new MetricRegistry();
        final DropwizardMetricConsumer metricConsumer =
                new DropwizardMetricConsumer(metricRegistry, metricNameFunc);

        final DummyRequestContext requestContext = new DummyRequestContext();
        final RequestLog requestLog = mock(RequestLog.class);
        final ResponseLog responseLog = mock(ResponseLog.class);

        when(requestLog.scheme()).thenReturn(Scheme.parse("none+http"));
        when(requestLog.path()).thenReturn("/bar");
        when(requestLog.method()).thenReturn("GET");
        when(requestLog.contentLength()).thenReturn(123L);
        when(responseLog.request()).thenReturn(requestLog);
        when(responseLog.statusCode()).thenReturn(200);
        when(responseLog.contentLength()).thenReturn(456L);
        when(responseLog.responseTimeNanos()).thenReturn(13L);

        metricConsumer.onRequest(requestContext, requestLog);
        final Map<String, Counter> counters = metricRegistry.getCounters();
        assertThat(counters.get("foo./bar#GET.activeRequests").getCount()).isEqualTo(1);

        metricConsumer.onResponse(requestContext, responseLog);
        final Map<String, Timer> timers = metricRegistry.getTimers();
        final Map<String, Meter> meters = metricRegistry.getMeters();
        assertThat(timers.get("foo./bar#GET.requests").getCount()).isEqualTo(1);
        assertThat(timers.get("foo./bar#GET.requests").getSnapshot().getValues()).containsExactly(13);
        assertThat(meters.get("foo./bar#GET.successes").getCount()).isEqualTo(1);
        assertThat(meters.get("foo./bar#GET.failures").getCount()).isEqualTo(0);
        assertThat(counters.get("foo./bar#GET.activeRequests").getCount()).isEqualTo(0);
        assertThat(meters.get("foo./bar#GET.requestBytes").getCount()).isEqualTo(123);
        assertThat(meters.get("foo./bar#GET.responseBytes").getCount()).isEqualTo(456);
    }

    private static class DummyRequestContext extends NonWrappingRequestContext {
        DummyRequestContext() {
            super(SessionProtocol.HTTP, "GET", "/", new DefaultHttpRequest());
        }

        @Override
        public EventLoop eventLoop() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Channel channel() {
            return null;
        }

        @Override
        public RequestLogBuilder requestLogBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResponseLogBuilder responseLogBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<RequestLog> requestLogFuture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ResponseLog> responseLogFuture() {
            throw new UnsupportedOperationException();
        }
    }
}
