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

import static com.linecorp.armeria.internal.brave.TraceContextUtil.ensureScopeUsesRequestContext;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.SpanContextUtil;
import com.linecorp.armeria.internal.brave.SpanTags;
import com.linecorp.armeria.internal.brave.TraceContextUtil;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.http.HttpServerHandler;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;
import brave.http.HttpTracing;

/**
 * Decorates a {@link Service} to trace inbound {@link HttpRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class BraveService extends SimpleDecoratingHttpService {
    /**
     * Creates a new tracing {@link Service} decorator using the specified {@link Tracing} instance.
     */
    public static Function<Service<HttpRequest, HttpResponse>, BraveService>
    newDecorator(Tracing tracing) {
        return newDecorator(HttpTracing.newBuilder(tracing)
                                       .serverParser(ArmeriaHttpServerParser.get())
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
    private final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;

    /**
     * Creates a new instance.
     */
    private BraveService(Service<HttpRequest, HttpResponse> delegate, HttpTracing httpTracing) {
        super(delegate);
        tracer = httpTracing.tracing().tracer();
        handler = HttpServerHandler.create(httpTracing);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Span span = handler.handleReceive(ServiceRequestContextAdapter.asHttpServerRequest(ctx));

        // Ensure the trace context propagates to children
        ctx.onChild(TraceContextUtil::copy);

        // For no-op spans, nothing special to do.
        if (span.isNoop()) {
            try (SpanInScope ignored = tracer.withSpanInScope(span)) {
                return delegate().serve(ctx, req);
            }
        }

        ctx.log().addListener(log -> SpanContextUtil.startSpan(span, log),
                              RequestLogAvailability.REQUEST_START);

        ctx.log().addListener(log -> {
            SpanTags.logWireReceive(span, log.requestFirstBytesTransferredTimeNanos(), log);
            // If the client timed-out the request, we will have never sent any response data at all.
            if (log.isAvailable(RequestLogAvailability.RESPONSE_FIRST_BYTES_TRANSFERRED)) {
                SpanTags.logWireSend(span, log.responseFirstBytesTransferredTimeNanos(), log);
            }
            final HttpServerResponse response = ServiceRequestContextAdapter.asHttpServerResponse(ctx);
            handler.handleSend(response, log.responseCause(), span);
        }, RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().serve(ctx, req);
        }
    }
}
