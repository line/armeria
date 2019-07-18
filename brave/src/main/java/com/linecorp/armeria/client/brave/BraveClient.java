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

package com.linecorp.armeria.client.brave;

import static com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext.ensureScopeUsesRequestContext;
import static com.linecorp.armeria.internal.brave.SpanTags.WIRE_RECEIVE_ANNOTATION;
import static com.linecorp.armeria.internal.brave.SpanTags.WIRE_SEND_ANNOTATION;

import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.AsciiStringKeyFactory;
import com.linecorp.armeria.internal.brave.SpanContextUtil;
import com.linecorp.armeria.internal.brave.SpanTags;
import com.linecorp.armeria.internal.brave.TraceContextUtil;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.http.HttpClientHandler;
import brave.http.HttpClientParser;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;

/**
 * Decorates a {@link Client} to trace outbound {@link HttpRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class BraveClient extends SimpleDecoratingClient<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(BraveClient.class);

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Tracing} instance.
     */
    public static Function<Client<HttpRequest, HttpResponse>, BraveClient> newDecorator(Tracing tracing) {
        return newDecorator(tracing, null);
    }

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Tracing} instance
     * and the remote service name.
     */
    public static Function<Client<HttpRequest, HttpResponse>, BraveClient> newDecorator(
            Tracing tracing, @Nullable String remoteServiceName) {
        HttpTracing httpTracing = HttpTracing.newBuilder(tracing)
                                             .clientParser(new ArmeriaHttpClientParser())
                                             .build();
        if (remoteServiceName != null) {
            httpTracing = httpTracing.clientOf(remoteServiceName);
        }
        return newDecorator(httpTracing);
    }

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link HttpTracing} instance.
     */
    public static Function<Client<HttpRequest, HttpResponse>, BraveClient> newDecorator(
            HttpTracing httpTracing) {
        try {
            ensureScopeUsesRequestContext(httpTracing.tracing());
        } catch (IllegalStateException e) {
            logger.warn("{} - it is appropriate to ignore this warning if this client is not being used " +
                        "inside an Armeria server (e.g., this is a normal spring-mvc tomcat server).",
                        e.getMessage());
        }
        return delegate -> new BraveClient(delegate, httpTracing);
    }

    private final Tracer tracer;
    private final TraceContext.Injector<RequestHeadersBuilder> injector;
    private final HttpClientHandler<RequestLog, RequestLog> handler;
    private final CurrentTraceContext currentTraceContext;
    private final ArmeriaHttpClientAdapter adapter;
    private final HttpClientParser clientParser;

    /**
     * Creates a new instance.
     */
    private BraveClient(Client<HttpRequest, HttpResponse> delegate, HttpTracing httpTracing) {
        super(delegate);
        currentTraceContext = httpTracing.tracing().currentTraceContext();
        tracer = httpTracing.tracing().tracer();
        clientParser = httpTracing.clientParser();
        adapter = new ArmeriaHttpClientAdapter();
        handler = HttpClientHandler.create(httpTracing, adapter);
        injector = httpTracing.tracing().propagationFactory().create(AsciiStringKeyFactory.INSTANCE)
                              .injector(RequestHeadersBuilder::set);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        final Span span = handler.handleSend(injector, newHeaders, ctx.log());
        req = HttpRequest.of(req, newHeaders.build());
        ctx.updateRequest(req);

        // Ensure the trace context propagates to children
        ctx.onChild(TraceContextUtil::copy);

        // For no-op spans, we only need to inject into headers and don't set any other attributes.
        if (span.isNoop()) {
            try (SpanInScope ignored = tracer.withSpanInScope(span)) {
                return delegate().execute(ctx, req);
            }
        }

        ctx.log().addListener(log -> SpanContextUtil.startSpan(span, log),
                              RequestLogAvailability.REQUEST_START);

        ctx.log().addListener(log -> {
            // The request might have failed even before it's sent, e.g. validation failure, connection error.
            if (log.isAvailable(RequestLogAvailability.REQUEST_FIRST_BYTES_TRANSFERRED)) {
                SpanTags.logWireSend(span, log.requestFirstBytesTransferredTimeNanos(), log);
            }
            // If the client timed-out the request, we will have never received any response data at all.
            if (log.isAvailable(RequestLogAvailability.RESPONSE_FIRST_BYTES_TRANSFERRED)) {
                SpanTags.logWireReceive(span, log.responseFirstBytesTransferredTimeNanos(), log);
            }
            handleFinish(log, span);
        }, RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().execute(ctx, req);
        }
    }

    /**
     * Copy from brave.http.HttpHandler#handleFinish(Object, Throwable, Span)
     * We need to set timestamp from armeria's clock instead of brave's one. But current implementation
     * of HttpHandler doesn't allow us to pass in our own timestamp.
     * https://github.com/openzipkin/brave/issues/946
     */
    private void handleFinish(RequestLog requestLog, Span span) {
        if (span.isNoop()) {
            return;
        }
        try {
            try (Scope ws = currentTraceContext.maybeScope(span.context())) {
                clientParser.response(adapter, requestLog, requestLog.responseCause(), span.customizer());
            }
            // close the scope before finishing the span
        } finally {
            finishInNullScope(span, requestLog);
        }
    }

    private void finishInNullScope(Span span, RequestLog requestLog) {
        try (Scope ws = currentTraceContext.maybeScope(null)) {
            span.finish(SpanContextUtil.wallTimeMicros(requestLog, requestLog.responseEndTimeNanos()));
        }
    }
}
