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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.internal.common.brave.SpanTags;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
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

    private static final Scope SERVICE_REQUEST_DECORATING_SCOPE = new Scope() {
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
                                       .serverRequestParser(ArmeriaHttpServerParser.get())
                                       .serverResponseParser(ArmeriaHttpServerParser.get())
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

        // Run the scope decorators when the ctx is pushed to the thread local.
        ctx.hook(() -> currentTraceContext.decorateScope(span.context(),
                                                         SERVICE_REQUEST_DECORATING_SCOPE)::close);

        // For no-op spans, nothing special to do.
        if (span.isNoop()) {
            try (SpanInScope ignored = tracer.withSpanInScope(span)) {
                return unwrap().serve(ctx, req);
            }
        }

        ctx.log().whenComplete().thenAccept(log -> {
            span.start(log.requestStartTimeMicros());

            final Long wireReceiveTimeNanos = log.requestFirstBytesTransferredTimeNanos();
            assert wireReceiveTimeNanos != null;
            SpanTags.logWireReceive(span, wireReceiveTimeNanos, log);

            final Long wireSendTimeNanos = log.responseFirstBytesTransferredTimeNanos();
            if (wireSendTimeNanos != null) {
                SpanTags.logWireSend(span, wireSendTimeNanos, log);
            } else {
                // If the client timed-out the request, we will have never sent any response data at all.
            }

            final HttpServerResponse braveRes =
                    ServiceRequestContextAdapter.asHttpServerResponse(log, braveReq);
            handler.handleSend(braveRes, span);
        });

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return unwrap().serve(ctx, req);
        }
    }
}
