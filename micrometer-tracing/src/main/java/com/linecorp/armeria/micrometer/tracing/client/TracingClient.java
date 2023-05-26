/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.micrometer.tracing.client;

import static com.linecorp.armeria.micrometer.tracing.internal.TraceContextUtil.ensureScopeUsesRequestContext;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.micrometer.tracing.internal.SpanTags;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.CurrentTraceContext.Scope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.http.HttpClientHandler;
import io.micrometer.tracing.http.HttpClientRequest;
import io.micrometer.tracing.http.HttpClientResponse;

/**
 * Decorates an {@link HttpClient} to trace outbound {@link HttpRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class TracingClient extends SimpleDecoratingHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(TracingClient.class);

    /**
     * Creates a new tracing {@link HttpClient} decorator using the specified {@link Tracer} instance.
     */
    public static Function<? super HttpClient, TracingClient> newDecorator(
            Tracer tracer, HttpClientHandler handler) {
        try {
            ensureScopeUsesRequestContext(tracer);
        } catch (IllegalStateException e) {
            logger.warn("{} - it is appropriate to ignore this warning if this client is not being used " +
                        "inside an Armeria server (e.g., this is a normal spring-mvc tomcat server).",
                        e.getMessage());
        }
        return delegate -> new TracingClient(delegate, tracer, handler);
    }

    private final HttpClientHandler handler;
    @Nullable
    private final CurrentTraceContext currentTraceContext;

    /**
     * Creates a new instance.
     */
    private TracingClient(HttpClient delegate, Tracer tracer, HttpClientHandler handler) {
        super(delegate);
        this.handler = handler;
        this.currentTraceContext = tracer.currentTraceContext();
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        final HttpClientRequest tracingReq = ClientRequestContextAdapter.asHttpClientRequest(ctx, newHeaders);
        final Span span = handler.handleSend(tracingReq);
        req = req.withHeaders(newHeaders);
        ctx.updateRequest(req);

        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
        if (currentTraceContext != null && !span.isNoop() && ctxExtension != null) {
            // Make the span the current span and run scope decorators when the ctx is pushed.
            ctxExtension.hook(() -> currentTraceContext.newScope(span.context()));
        }

        maybeAddTagsToSpan(ctx, tracingReq, span);
        // don't use Tracer apis since it might use a different CurrentTraceContext internally
        try (Scope ignored = currentTraceContext.newScope(span.context())) {
            return unwrap().execute(ctx, req);
        }
    }

    private void maybeAddTagsToSpan(ClientRequestContext ctx, HttpClientRequest braveReq, Span span) {
        if (span.isNoop()) {
            // For no-op spans, we only need to inject into headers and don't set any other attributes.
            return;
        }

        ctx.log().whenComplete().thenAccept(log -> {

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

            final HttpClientResponse braveRes = ClientRequestContextAdapter.asHttpClientResponse(log, braveReq);
            handler.handleReceive(braveRes, span);
        });
    }

    private static void logTiming(Span span, String startName, String endName, long startTimeMicros,
                                  long durationNanos) {
        span.event(startName, startTimeMicros, TimeUnit.MICROSECONDS);
        span.event(endName, startTimeMicros + TimeUnit.NANOSECONDS.toMicros(durationNanos),
                   TimeUnit.MICROSECONDS);
    }
}
