/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Span;
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
public final class BraveService extends AbstractBraveService<HttpServerRequest, HttpServerResponse,
        HttpRequest, HttpResponse> implements HttpService {

    @VisibleForTesting
    static final HttpRequestParser defaultRequestParser = (request, context, span) -> {
        HttpRequestParser.DEFAULT.parse(request, context, span);
        BraveServerParsers.httpRequestParser().parse(request, context, span);
    };

    @VisibleForTesting
    static final HttpResponseParser defaultResponseParser = (response, context, span) -> {
        HttpResponseParser.DEFAULT.parse(response, context, span);
        BraveServerParsers.httpResponseParser().parse(response, context, span);
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

    private final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;

    /**
     * Creates a new instance.
     */
    private BraveService(HttpService delegate, HttpTracing httpTracing) {
        super(delegate, httpTracing.tracing().tracer(),
              (RequestContextCurrentTraceContext) httpTracing.tracing().currentTraceContext());
        handler = HttpServerHandler.create(httpTracing);
    }

    @Override
    HttpServerRequest braveRequest(ServiceRequestContext ctx) {
        return ServiceRequestContextAdapter.asHttpServerRequest(ctx);
    }

    @Override
    HttpServerResponse braveResponse(ServiceRequestContext ctx, RequestLog log, HttpServerRequest braveReq) {
        return ServiceRequestContextAdapter.asHttpServerResponse(log, braveReq);
    }

    @Override
    Span handleReceive(HttpServerRequest braveReq) {
        return handler.handleReceive(braveReq);
    }

    @Override
    void handleSend(HttpServerResponse response, Span span) {
        handler.handleSend(response, span);
    }
}
