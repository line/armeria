/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.brave;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

import brave.Span;

class SpanTagsTest {

    @Test
    void http() {
        final Span span = mockSpan();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/post",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final ServiceRequestContext ctx = newContext(req, SessionProtocol.H2C, 200);

        SpanTags.addTags(span, ctx.log());

        verify(span, times(1)).tag("http.host", "foo.com");
        verify(span, times(1)).tag("http.method", "POST");
        verify(span, times(1)).tag("http.path", "/post");
        verify(span, times(1)).tag("http.url", "http://foo.com/post");
        verify(span, times(1)).tag("http.status_code", "200");
        verify(span, times(1)).tag("http.protocol", "h2c");
        verify(span, times(1)).tag(eq("address.remote"), notNull());
        verify(span, times(1)).tag(eq("address.local"), notNull());
        verifyNoMoreInteractions(span);
    }

    @Test
    void https() {
        final Span span = mockSpan();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/get",
                                                                 HttpHeaderNames.AUTHORITY, "bar.com"));
        final ServiceRequestContext ctx = newContext(req, SessionProtocol.H2, 404);

        SpanTags.addTags(span, ctx.log());

        verify(span, times(1)).tag("http.host", "bar.com");
        verify(span, times(1)).tag("http.method", "GET");
        verify(span, times(1)).tag("http.path", "/get");
        verify(span, times(1)).tag("http.url", "https://bar.com/get");
        verify(span, times(1)).tag("http.status_code", "404");
        verify(span, times(1)).tag("http.protocol", "h2");
        verify(span, times(1)).tag(eq("address.remote"), notNull());
        verify(span, times(1)).tag(eq("address.local"), notNull());
        verifyNoMoreInteractions(span);
    }

    @Test
    void rpc() {
        final Span span = mockSpan();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/rpc",
                                                                 HttpHeaderNames.AUTHORITY, "call.com"));
        final ServiceRequestContext ctx = newContext(req, SessionProtocol.H2C, 200, logBuilder -> {
            logBuilder.serializationFormat(SerializationFormat.UNKNOWN);
            logBuilder.requestContent(RpcRequest.of(Object.class, "someMethod"), null);
        });

        SpanTags.addTags(span, ctx.log());

        verify(span, times(1)).tag("http.host", "call.com");
        verify(span, times(1)).tag("http.method", "POST");
        verify(span, times(1)).tag("http.path", "/rpc");
        verify(span, times(1)).tag("http.url", "http://call.com/rpc");
        verify(span, times(1)).tag("http.status_code", "200");
        verify(span, times(1)).tag("http.protocol", "h2c");
        verify(span, times(1)).tag("http.serfmt", "unknown");
        verify(span, times(1)).tag(eq("address.remote"), notNull());
        verify(span, times(1)).tag(eq("address.local"), notNull());
        verify(span, times(1)).name("someMethod");
        verifyNoMoreInteractions(span);
    }

    @Test
    void error() {
        final Span span = mockSpan();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.CONNECT, "/error",
                                                                 HttpHeaderNames.AUTHORITY, "oops.com"));
        final ServiceRequestContext ctx = newContext(req, SessionProtocol.H2C, 0, logBuilder -> {
            logBuilder.endResponse(new Exception("oops"));
        });

        SpanTags.addTags(span, ctx.log());

        verify(span, times(1)).tag("http.host", "oops.com");
        verify(span, times(1)).tag("http.method", "CONNECT");
        verify(span, times(1)).tag("http.path", "/error");
        verify(span, times(1)).tag("http.url", "http://oops.com/error");
        verify(span, times(1)).tag("http.status_code", "0");
        verify(span, times(1)).tag("http.protocol", "h2c");
        verify(span, times(1)).tag("error", "java.lang.Exception: oops");
        verify(span, times(1)).tag(eq("address.remote"), notNull());
        verify(span, times(1)).tag(eq("address.local"), notNull());
        verifyNoMoreInteractions(span);
    }

    private static Span mockSpan() {
        final Span span = mock(Span.class);
        when(span.tag(any(), any())).thenReturn(span);
        return span;
    }

    private static ServiceRequestContext newContext(HttpRequest req, SessionProtocol protocol, int statusCode) {
        return newContext(req, protocol, statusCode, logBuilder -> {});
    }

    private static ServiceRequestContext newContext(HttpRequest req, SessionProtocol protocol, int statusCode,
                                                    Consumer<RequestLogBuilder> logBuilderCustomizer) {
        final ServiceRequestContextBuilder builder = ServiceRequestContextBuilder.of(req);
        builder.sessionProtocol(protocol);
        if (protocol.isTls()) {
            builder.sslSession(mock(SSLSession.class));
        }
        final ServiceRequestContext ctx = builder.build();
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilderCustomizer.accept(logBuilder);
        logBuilder.endRequest();
        logBuilder.responseHeaders(ResponseHeaders.of(statusCode));
        logBuilder.endResponse();
        return ctx;
    }
}
