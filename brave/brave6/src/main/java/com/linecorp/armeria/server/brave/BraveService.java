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

import static com.linecorp.armeria.internal.common.brave.TraceContextUtil.ensureScopeUsesRequestContext;
import static com.linecorp.armeria.server.brave.ArmeriaServerParser.annotateWireSpan;

import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.http.HttpRequestParser;
import brave.http.HttpResponseParser;
import brave.http.HttpServerHandler;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext.Scope;

/**
 * Decorates an {@link HttpService} to trace inbound {@link HttpRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class BraveService extends SimpleDecoratingHttpService {

    @VisibleForTesting
    static final HttpRequestParser defaultRequestParser = (request, context, span) -> {
        HttpRequestParser.DEFAULT.parse(request, context, span);
        ArmeriaHttpServerParser.requestParser().parse(request, context, span);
    };

    @VisibleForTesting
    static final HttpResponseParser defaultResponseParser = (response, context, span) -> {
        HttpResponseParser.DEFAULT.parse(response, context, span);
        ArmeriaHttpServerParser.responseParser().parse(response, context, span);
    };

    static final Scope SERVICE_REQUEST_DECORATING_SCOPE = new Scope() {
        @Override
        public void close() {}

        @Override
        public String toString() {
            return "ServiceRequestDecoratingScope";
        }
    };

    /**
     * Creates a new tracing {@link HttpService} decorator using the specified {@link Tracing} instance.
     */
    public static Function<? super HttpService, BraveService>
    newDecorator(Tracing tracing) {
        return newDecorator(HttpTracing.newBuilder(tracing)
                                       .serverRequestParser(defaultRequestParser)
                                       .serverResponseParser(defaultResponseParser)
                                       .build());
    }

    /**
     * Creates a new tracing {@link HttpService} decorator using the specified {@link HttpTracing} instance.
     */
    public static Function<? super HttpService, BraveService>
    newDecorator(HttpTracing httpTracing) {
        ensureScopeUsesRequestContext(httpTracing.tracing());
        return service -> new BraveService(service, httpTracing);
    }

    private final Tracer tracer;
    private final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;
    private final RequestContextCurrentTraceContext currentTraceContext;

    /**
     * Creates a new instance.
     */
    private BraveService(HttpService delegate, HttpTracing httpTracing) {
        super(delegate);
        final Tracing tracing = httpTracing.tracing();
        tracer = tracing.tracer();
        handler = HttpServerHandler.create(httpTracing);
        currentTraceContext = (RequestContextCurrentTraceContext) tracing.currentTraceContext();
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (!ctx.config().transientServiceOptions().contains(TransientServiceOption.WITH_TRACING)) {
            return unwrap().serve(ctx, req);
        }

        final HttpServerRequest braveReq = ServiceRequestContextAdapter.asHttpServerRequest(ctx);
        final Span span = handler.handleReceive(braveReq);

        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
        if (currentTraceContext.scopeDecoratorAdded() && !span.isNoop() && ctxExtension != null) {
            // Run the scope decorators when the ctx is pushed to the thread local.
            ctxExtension.hook(() -> currentTraceContext.decorateScope(span.context(),
                                                                      SERVICE_REQUEST_DECORATING_SCOPE));
        }

        maybeAddTagsToSpan(ctx, braveReq, span);
        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return unwrap().serve(ctx, req);
        }
    }

    private void maybeAddTagsToSpan(ServiceRequestContext ctx, HttpServerRequest braveReq, Span span) {
        if (span.isNoop()) {
            // For no-op spans, nothing special to do.
            return;
        }

        ctx.log().whenComplete().thenAccept(log -> {
            annotateWireSpan(log, span);
            final HttpServerResponse braveRes =
                    ServiceRequestContextAdapter.asHttpServerResponse(log, braveReq);
            handler.handleSend(braveRes, span);
        });
    }
}
