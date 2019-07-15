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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext.ensureScopeUsesRequestContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
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
import brave.Span.Kind;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.propagation.TraceContext;

/**
 * Decorates a {@link Client} to trace outbound {@link HttpRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class BraveClient extends SimpleDecoratingHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(BraveClient.class);

    // TODO(minwoox) Add the variant which takes HttpTracing.

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Tracing} instance.
     */
    public static Function<Client<HttpRequest, HttpResponse>, BraveClient> newDecorator(Tracing tracing) {
        return newDecorator(tracing, null);
    }

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Tracing} instance
     * and remote service name.
     */
    public static Function<Client<HttpRequest, HttpResponse>, BraveClient> newDecorator(
            Tracing tracing, @Nullable String remoteServiceName) {
        try {
            ensureScopeUsesRequestContext(tracing);
        } catch (IllegalStateException e) {
            logger.warn("{} - it is appropriate to ignore this warning if this client is not being used " +
                        "inside an Armeria server (e.g., this is a normal spring-mvc tomcat server).",
                        e.getMessage());
        }
        return delegate -> new BraveClient(delegate, tracing, remoteServiceName);
    }

    private final Tracer tracer;
    private final TraceContext.Injector<RequestHeadersBuilder> injector;
    @Nullable
    private final String remoteServiceName;

    /**
     * Creates a new instance.
     */
    private BraveClient(Client<HttpRequest, HttpResponse> delegate, Tracing tracing,
                        @Nullable String remoteServiceName) {
        super(delegate);
        tracer = tracing.tracer();
        injector = tracing.propagationFactory().create(AsciiStringKeyFactory.INSTANCE)
                          .injector(RequestHeadersBuilder::set);
        this.remoteServiceName = remoteServiceName;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Span span = tracer.nextSpan();

        // Inject the headers.
        final RequestHeadersBuilder newHeaders = req.headers().toBuilder();
        injector.inject(span.context(), newHeaders);
        req = HttpRequest.of(req, newHeaders.build());
        ctx.updateRequest(req);

        // For no-op spans, we only need to inject into headers and don't set any other attributes.
        if (span.isNoop()) {
            return delegate().execute(ctx, req);
        }

        final String method = ctx.method().name();
        span.kind(Kind.CLIENT).name(method);
        ctx.log().addListener(log -> SpanContextUtil.startSpan(span, log),
                              RequestLogAvailability.REQUEST_START);

        // Ensure the trace context propagates to children
        ctx.onChild(TraceContextUtil::copy);

        ctx.log().addListener(log -> {
            SpanTags.logWireSend(span, log.requestFirstBytesTransferredTimeNanos(), log);

            // If the client timed-out the request, we will have never received any response data at all.
            if (log.isAvailable(RequestLogAvailability.RESPONSE_FIRST_BYTES_TRANSFERRED)) {
                SpanTags.logWireReceive(span, log.responseFirstBytesTransferredTimeNanos(), log);
            }

            finishSpan(span, log);
        }, RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().execute(ctx, req);
        }
    }

    private void finishSpan(Span span, RequestLog log) {
        setRemoteEndpoint(span, log);
        SpanContextUtil.closeSpan(span, log);
    }

    private void setRemoteEndpoint(Span span, RequestLog log) {
        final SocketAddress remoteAddress = log.context().remoteAddress();
        final InetAddress address;
        final int port;
        if (remoteAddress instanceof InetSocketAddress) {
            final InetSocketAddress socketAddress = (InetSocketAddress) remoteAddress;
            address = socketAddress.getAddress();
            port = socketAddress.getPort();
        } else {
            address = null;
            port = 0;
        }
        if (!isNullOrEmpty(remoteServiceName)) {
            span.remoteServiceName(remoteServiceName);
        }
        if (address != null) {
            span.remoteIpAndPort(address.getHostAddress(), port);
        }
    }
}
