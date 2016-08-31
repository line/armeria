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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;

import io.netty.channel.EventLoop;
import io.netty.util.Attribute;

public class DropwizardMetricConsumerTest {

    @Test
    public void testMetricsForHttp() {
        final MetricRegistry metricRegistry = new MetricRegistry();
        final DropwizardMetricConsumer metricConsumer = new DropwizardMetricConsumer(metricRegistry, "foo");

        final RequestContext ctx = mock(RequestContext.class);
        final RequestLog requestLog = mock(RequestLog.class);
        @SuppressWarnings("unchecked")
        final Attribute<HttpHeaders> attribute = (Attribute<HttpHeaders>) mock(Attribute.class);
        when(requestLog.hasAttr(RequestLog.HTTP_HEADERS)).thenReturn(true);
        when(requestLog.attr(RequestLog.HTTP_HEADERS)).thenReturn(attribute);
        when(attribute.get()).thenReturn(new DefaultHttpHeaders().method(HttpMethod.GET).path("/bar"));

        final DummyRequestContext requestContext = new DummyRequestContext();
        metricConsumer.onRequest(requestContext, requestLog);

        assertEquals(1L, metricRegistry.getCounters().get("foo./bar#GET.activeRequests").getCount());

        final ResponseLog responseLog = mock(ResponseLog.class);

        when(requestLog.scheme()).thenReturn(Scheme.parse("none+http"));
        when(requestLog.contentLength()).thenReturn(123L);
        when(requestLog.startTimeNanos()).thenReturn(1L);
        when(responseLog.request()).thenReturn(requestLog);
        when(responseLog.statusCode()).thenReturn(200);
        when(responseLog.contentLength()).thenReturn(456L);
        when(responseLog.endTimeNanos()).thenReturn(13L);
        metricConsumer.onResponse(requestContext, responseLog);

        assertEquals(1L, metricRegistry.getTimers().get("foo./bar#GET.requests").getCount());
        assertEquals(1L, metricRegistry.getMeters().get("foo./bar#GET.successes").getCount());
        assertEquals(0L, metricRegistry.getMeters().get("foo./bar#GET.failures").getCount());
        assertEquals(0L, metricRegistry.getCounters().get("foo./bar#GET.activeRequests").getCount());
        assertEquals(123L, metricRegistry.getMeters().get("foo./bar#GET.requestBytes").getCount());
        assertEquals(456L, metricRegistry.getMeters().get("foo./bar#GET.responseBytes").getCount());
    }

    @Test
    public void testMetricsForRpc() {
        final MetricRegistry metricRegistry = new MetricRegistry();
        final DropwizardMetricConsumer metricConsumer = new DropwizardMetricConsumer(metricRegistry, "foo");

        final RequestContext ctx = mock(RequestContext.class);
        final RequestLog requestLog = mock(RequestLog.class);
        @SuppressWarnings("unchecked")
        final Attribute<RpcRequest> attribute = (Attribute<RpcRequest>) mock(Attribute.class);
        when(requestLog.hasAttr(RequestLog.RPC_REQUEST)).thenReturn(true);
        when(requestLog.attr(RequestLog.RPC_REQUEST)).thenReturn(attribute);
        when(attribute.get()).thenReturn(new DummyRpcRequest("dummy"));

        final DummyRequestContext requestContext = new DummyRequestContext();
        metricConsumer.onRequest(requestContext, requestLog);

        assertEquals(1L, metricRegistry.getCounters().get("foo.dummy.activeRequests").getCount());

        final ResponseLog responseLog = mock(ResponseLog.class);

        when(requestLog.scheme()).thenReturn(Scheme.parse("tbinary+http"));
        when(requestLog.contentLength()).thenReturn(123L);
        when(requestLog.startTimeNanos()).thenReturn(1L);
        when(responseLog.request()).thenReturn(requestLog);
        when(responseLog.statusCode()).thenReturn(200);
        when(responseLog.contentLength()).thenReturn(456L);
        when(responseLog.endTimeNanos()).thenReturn(13L);
        metricConsumer.onResponse(requestContext, responseLog);

        assertEquals(1L, metricRegistry.getTimers().get("foo.dummy.requests").getCount());
        assertEquals(1L, metricRegistry.getMeters().get("foo.dummy.successes").getCount());
        assertEquals(0L, metricRegistry.getMeters().get("foo.dummy.failures").getCount());
        assertEquals(0L, metricRegistry.getCounters().get("foo.dummy.activeRequests").getCount());
        assertEquals(123L, metricRegistry.getMeters().get("foo.dummy.requestBytes").getCount());
        assertEquals(456L, metricRegistry.getMeters().get("foo.dummy.responseBytes").getCount());
    }

    private class DummyRequestContext extends NonWrappingRequestContext {
        DummyRequestContext() {
            super(SessionProtocol.HTTP, "GET", "/", new DefaultHttpRequest());
        }

        @Override
        public EventLoop eventLoop() {
            throw new UnsupportedOperationException();
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

    private class DummyRpcRequest implements RpcRequest {

        private final String method;

        DummyRpcRequest(String method) {
            this.method = method;
        }

        @Override
        public Class<?> serviceType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public List<Object> params() {
            throw new UnsupportedOperationException();
        }
    }
}
