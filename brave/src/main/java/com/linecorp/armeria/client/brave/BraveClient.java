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

import static com.linecorp.armeria.internal.common.brave.TraceContextUtil.ensureScopeUsesRequestContext;

import java.util.concurrent.TimeUnit;
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
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.brave.SpanTags;

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
        req = req.withHeaders(newHeaders);
        ctx.updateRequest(req);

        // For no-op spans, we only need to inject into headers and don't set any other attributes.
        if (span.isNoop()) {
            try (SpanInScope ignored = tracer.withSpanInScope(span)) {
                return delegate().execute(ctx, req);
            }
        }

        ctx.log().whenComplete().thenAccept(log -> {
            span.start(log.requestStartTimeMicros());

            final Long wireSendTimeNanos = log.requestFirstBytesTransferredTimeNanos();
            if (wireSendTimeNanos != null) {
                SpanTags.logWireSend(span, wireSendTimeNanos, log);
            } else {
                // The request might have failed even before it's sent,
                // e.g. validation failure, connection error.
            }

            final Long wireReceiveTimeNanos = log.responseFirstBytesTransferredTimeNanos();
            if (wireReceiveTimeNanos != null) {
                SpanTags.logWireReceive(span, wireReceiveTimeNanos, log);
            } else {
                // If the client timed-out the request, we will have never received any response data at all.
            }

            SpanTags.updateRemoteEndpoint(span, ctx);

            final ClientConnectionTimings timings = log.connectionTimings();
            if (timings != null) {
                logTiming(span, "connection-acquire.start", "connection-acquire.end",
                          timings.connectionAcquisitionStartTimeMicros(),
                          timings.connectionAcquisitionDurationNanos());
                if (timings.dnsResolutionDurationNanos() != -1) {
                    logTiming(span, "dns-resolve.start", "dns-resolve.end",
                              timings.dnsResolutionStartTimeMicros(),
                              timings.dnsResolutionDurationNanos());
                }
                if (timings.socketConnectDurationNanos() != -1) {
                    logTiming(span, "socket-connect.start", "socket-connect.end",
                              timings.socketConnectStartTimeMicros(),
                              timings.socketConnectDurationNanos());
                }
                if (timings.pendingAcquisitionDurationNanos() != -1) {
                    logTiming(span, "connection-reuse.start", "connection-reuse.end",
                              timings.pendingAcquisitionStartTimeMicros(),
                              timings.pendingAcquisitionDurationNanos());
                }
            }

            final HttpClientResponse response = ClientRequestContextAdapter.asHttpClientResponse(log);
            try (SafeCloseable ignored = ctx.push()) {
                handler.handleReceive(response, log.responseCause(), span);
            }
        });

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().execute(ctx, req);
        }
    }

    private static void logTiming(Span span, String startName, String endName, long startTimeMicros,
                                  long durationNanos) {
        span.annotate(startTimeMicros, startName);
        span.annotate(startTimeMicros + TimeUnit.NANOSECONDS.toMicros(durationNanos), endName);
    }
}
