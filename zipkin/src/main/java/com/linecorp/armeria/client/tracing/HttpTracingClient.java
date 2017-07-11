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

package com.linecorp.armeria.client.tracing;

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.tracing.AsciiStringKeyFactory;
import com.linecorp.armeria.internal.tracing.SpanContextUtil;
import com.linecorp.armeria.internal.tracing.SpanTags;

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
        return delegate -> new HttpTracingClient(delegate, tracing);
    }

    private final Tracer tracer;
    private final TraceContext.Injector<HttpHeaders> injector;

    /**
     * Creates a new instance.
     */
    protected HttpTracingClient(Client<HttpRequest, HttpResponse> delegate, Tracing tracing) {
        super(delegate);
        this.tracer = tracing.tracer();
        injector = tracing.propagationFactory().create(AsciiStringKeyFactory.INSTANCE)
                          .injector(HttpHeaders::set);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        Span span = tracer.nextSpan();
        injector.inject(span.context(), req.headers());
        // For no-op spans, we only need to inject into headers and don't set any other attributes.
        if (span.isNoop()) {
            return delegate().execute(ctx, req);
        }

        final String method = ctx.method().name();
        span.kind(Kind.CLIENT).name(method).start();

        SpanContextUtil.setupContext(ctx, span, tracer);

        ctx.log().addListener(log -> finishSpan(span, log), RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().execute(ctx, req);
        }
    }

    private void finishSpan(Span span, RequestLog log) {
        SpanTags.addTags(span, log);
        span.finish();
    }
}
