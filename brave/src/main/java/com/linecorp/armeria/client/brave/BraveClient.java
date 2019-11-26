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

import static com.linecorp.armeria.internal.brave.TraceContextUtil.ensureScopeUsesRequestContext;

import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.SpanContextUtil;
import com.linecorp.armeria.internal.brave.SpanTags;
import com.linecorp.armeria.internal.brave.TraceContextUtil;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.http.HttpClientHandler;
import brave.http.HttpClientRequest;
import brave.http.HttpClientResponse;
import brave.http.HttpTracing;

/**
 * Decorates an {@link HttpClient} to trace outbound {@link HttpRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class BraveClient extends SimpleDecoratingHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(BraveClient.class);

    /**
     * Creates a new tracing {@link HttpClient} decorator using the specified {@link Tracing} instance.
     */
    public static Function<? super HttpClient, BraveClient> newDecorator(Tracing tracing) {
        return newDecorator(tracing, null);
    }

    /**
     * Creates a new tracing {@link HttpClient} decorator using the specified {@link Tracing} instance
     * and the remote service name.
     */
    public static Function<? super HttpClient, BraveClient> newDecorator(
            Tracing tracing, @Nullable String remoteServiceName) {
        HttpTracing httpTracing = HttpTracing.newBuilder(tracing)
                                             .clientParser(ArmeriaHttpClientParser.get())
                                             .build();
        if (remoteServiceName != null) {
            httpTracing = httpTracing.clientOf(remoteServiceName);
        }
        return newDecorator(httpTracing);
    }

    /**
     * Creates a new tracing {@link HttpClient} decorator using the specified {@link HttpTracing} instance.
     */
    public static Function<? super HttpClient, BraveClient> newDecorator(
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
    private final HttpClientHandler<HttpClientRequest, HttpClientResponse> handler;

    /**
     * Creates a new instance.
     */
    private BraveClient(HttpClient delegate, HttpTracing httpTracing) {
        super(delegate);
        tracer = httpTracing.tracing().tracer();
        handler = HttpClientHandler.create(httpTracing);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        final HttpClientRequest request = ClientRequestContextAdapter.asHttpClientRequest(ctx, newHeaders);
        final Span span = handler.handleSend(request);
        req = req.withHeaders(newHeaders.build());
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
            SpanTags.updateRemoteEndpoint(span, ctx);
            final HttpClientResponse response = ClientRequestContextAdapter.asHttpClientResponse(ctx);
            handler.handleReceive(response, log.responseCause(), span);
        }, RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().execute(ctx, req);
        }
    }
}
