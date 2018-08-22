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

package com.linecorp.armeria.client.tracing;

import static com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext.ensureScopeUsesRequestContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext;
import com.linecorp.armeria.internal.tracing.AsciiStringKeyFactory;
import com.linecorp.armeria.internal.tracing.SpanContextUtil;

import brave.Span;
import brave.Span.Kind;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.propagation.TraceContext;

/**
 * Decorates a {@link Client} to trace outbound {@link HttpRequest}s using
 * <a href="http://zipkin.io/">Zipkin</a>.
 *
 * <p>This decorator puts trace data into HTTP headers. The specifications of header names and its values
 * correspond to <a href="http://zipkin.io/">Zipkin</a>.
 */
public class HttpTracingClient extends SimpleDecoratingClient<HttpRequest, HttpResponse> {

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Tracing} instance.
     */
    public static Function<Client<HttpRequest, HttpResponse>, HttpTracingClient> newDecorator(Tracing tracing) {
        return newDecorator(tracing, null);
    }

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Tracing} instance
     * and remote service name.
     */
    public static Function<Client<HttpRequest, HttpResponse>, HttpTracingClient> newDecorator(
            Tracing tracing,
            @Nullable String remoteServiceName) {
        ensureScopeUsesRequestContext(tracing);
        return delegate -> new HttpTracingClient(delegate, tracing, remoteServiceName);
    }

    private final Tracer tracer;
    private final TraceContext.Injector<HttpHeaders> injector;
    @Nullable
    private final String remoteServiceName;

    /**
     * Creates a new instance.
     */
    protected HttpTracingClient(Client<HttpRequest, HttpResponse> delegate, Tracing tracing,
                                @Nullable String remoteServiceName) {
        super(delegate);
        tracer = tracing.tracer();
        injector = tracing.propagationFactory().create(AsciiStringKeyFactory.INSTANCE)
                          .injector(HttpHeaders::set);
        this.remoteServiceName = remoteServiceName;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Span span = tracer.nextSpan();
        injector.inject(span.context(), req.headers());
        // For no-op spans, we only need to inject into headers and don't set any other attributes.
        if (span.isNoop()) {
            return delegate().execute(ctx, req);
        }

        final String method = ctx.method().name();
        span.kind(Kind.CLIENT).name(method).start();

        // Ensure the trace context propagates to children
        ctx.onChild(RequestContextCurrentTraceContext::copy);

        ctx.log().addListener(log -> finishSpan(span, log), RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().execute(ctx, req);
        }
    }

    private void finishSpan(Span span, RequestLog log) {
        setRemoteEndpoint(span, log);
        SpanContextUtil.closeSpan(span, log);
    }

    private void setRemoteEndpoint(Span span, RequestLog log) {

        final SocketAddress remoteAddress = log.context().remoteAddress();
        final InetAddress address;
        final int port;
        if (remoteAddress instanceof InetSocketAddress) {
            final InetSocketAddress socketAddress = (InetSocketAddress) remoteAddress;
            address = socketAddress.getAddress();
            port = socketAddress.getPort();
        } else {
            address = null;
            port = 0;
        }

        final String remoteServiceName;
        if (this.remoteServiceName != null) {
            remoteServiceName = this.remoteServiceName;
        } else {
            final String authority = log.requestHeaders().authority();
            if (!"?".equals(authority)) {
                remoteServiceName = authority;
            } else if (address != null) {
                remoteServiceName = String.valueOf(remoteAddress);
            } else {
                remoteServiceName = null;
            }
        }

        if (remoteServiceName != null) {
            span.remoteServiceName(remoteServiceName);
        }
        if (address != null) {
            span.remoteIpAndPort(address.getHostAddress(), port);
        }
    }
}
