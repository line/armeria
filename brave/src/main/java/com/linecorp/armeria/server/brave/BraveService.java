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

package com.linecorp.armeria.server.brave;

import static com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext.ensureScopeUsesRequestContext;
import static com.linecorp.armeria.internal.brave.SpanTags.WIRE_RECEIVE_ANNOTATION;
import static com.linecorp.armeria.internal.brave.SpanTags.WIRE_SEND_ANNOTATION;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.AsciiStringKeyFactory;
import com.linecorp.armeria.internal.brave.TraceContextUtil;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.http.HttpServerHandler;
import brave.http.HttpTracing;
import brave.propagation.TraceContext;

/**
 * Decorates a {@link Service} to trace inbound {@link HttpRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class BraveService extends SimpleDecoratingService<HttpRequest, HttpResponse> {
    /**
     * Creates a new tracing {@link Service} decorator using the specified {@link Tracing} instance.
     */
    public static Function<Service<HttpRequest, HttpResponse>, BraveService>
    newDecorator(Tracing tracing) {
        return newDecorator(HttpTracing.newBuilder(tracing)
                                       .serverParser(new ArmeriaHttpServerParser())
                                       .build());
    }

    /**
     * Creates a new tracing {@link Service} decorator using the specified {@link HttpTracing} instance.
     */
    public static Function<Service<HttpRequest, HttpResponse>, BraveService>
    newDecorator(HttpTracing httpTracing) {
        ensureScopeUsesRequestContext(httpTracing.tracing());
        return service -> new BraveService(service, httpTracing);
    }

    private final Tracer tracer;
    private final TraceContext.Extractor<HttpHeaders> extractor;
    private final HttpServerHandler<RequestLog, RequestLog> handler;

    /**
     * Creates a new instance.
     */
    private BraveService(Service<HttpRequest, HttpResponse> delegate, HttpTracing httpTracing) {
        super(delegate);
        tracer = httpTracing.tracing().tracer();
        handler = HttpServerHandler.create(httpTracing, new ArmeriaHttpServerAdapter());
        extractor = httpTracing.tracing().propagationFactory()
                               .create(AsciiStringKeyFactory.INSTANCE)
                               .extractor(HttpHeaders::get);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Span span = handler.handleReceive(extractor, req.headers(), ctx.log());

        // Ensure the trace context propagates to children
        ctx.onChild(TraceContextUtil::copy);

        // For no-op spans, nothing special to do.
        if (span.isNoop()) {
            try (SpanInScope ignored = tracer.withSpanInScope(span)) {
                return delegate().serve(ctx, req);
            }
        }
        ctx.log().addListener(log -> span.annotate(WIRE_RECEIVE_ANNOTATION),
                              RequestLogAvailability.REQUEST_FIRST_BYTES_TRANSFERRED);

        ctx.log().addListener(log -> span.annotate(WIRE_SEND_ANNOTATION),
                              RequestLogAvailability.RESPONSE_FIRST_BYTES_TRANSFERRED);

        ctx.log().addListener(log -> handler.handleSend(log, log.responseCause(), span),
                              RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().serve(ctx, req);
        }
    }
}
