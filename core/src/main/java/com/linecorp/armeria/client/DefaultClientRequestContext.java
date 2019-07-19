/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.Attribute;

/**
 * Default {@link ClientRequestContext} implementation.
 */
public class DefaultClientRequestContext extends NonWrappingRequestContext implements ClientRequestContext {
    static final ThreadLocal<Consumer<ClientRequestContext>> THREAD_LOCAL_CONTEXT_CUSTOMIZER =
            new ThreadLocal<>();

    private final EventLoop eventLoop;
    private final ClientOptions options;
    private volatile Endpoint endpoint;
    @Nullable
    private final String fragment;

    private final DefaultRequestLog log;

    private long writeTimeoutMillis;
    private long responseTimeoutMillis;
    private long maxResponseLength;

    @Nullable
    private String strVal;

    @Nullable
    private volatile HttpHeaders additionalRequestHeaders;

    /**
     * Creates a new instance.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param request the request associated with this context
     */
    public DefaultClientRequestContext(
            EventLoop eventLoop, MeterRegistry meterRegistry,
            SessionProtocol sessionProtocol, Endpoint endpoint,
            HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
            ClientOptions options, Request request) {

        super(meterRegistry, sessionProtocol, method, path, query, request);

        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.options = requireNonNull(options, "options");
        this.endpoint = requireNonNull(endpoint, "endpoint");
        this.fragment = fragment;

        log = new DefaultRequestLog(this, options.requestContentPreviewerFactory(),
                                    options.responseContentPreviewerFactory());

        writeTimeoutMillis = options.writeTimeoutMillis();
        responseTimeoutMillis = options.responseTimeoutMillis();
        maxResponseLength = options.maxResponseLength();

        final HttpHeaders headers = options.getOrElse(ClientOption.HTTP_HEADERS, HttpHeaders.of());
        if (!headers.isEmpty()) {
            additionalRequestHeaders = headers;
        }

        runThreadLocalContextCustomizer();
    }

    private HttpHeaders createAdditionalHeadersIfAbsent() {
        final HttpHeaders additionalRequestHeaders = this.additionalRequestHeaders;
        if (additionalRequestHeaders == null) {
            return this.additionalRequestHeaders = HttpHeaders.of();
        } else {
            return additionalRequestHeaders;
        }
    }

    private void runThreadLocalContextCustomizer() {
        final Consumer<ClientRequestContext> customizer = THREAD_LOCAL_CONTEXT_CUSTOMIZER.get();
        if (customizer != null) {
            customizer.accept(this);
        }
    }

    private DefaultClientRequestContext(DefaultClientRequestContext ctx) {
        this(ctx, ctx.request());
    }

    private DefaultClientRequestContext(DefaultClientRequestContext ctx, Request request) {
        super(ctx.meterRegistry(), ctx.sessionProtocol(), ctx.method(), ctx.path(), ctx.query(), request);

        eventLoop = ctx.eventLoop();
        options = ctx.options();
        endpoint = ctx.endpoint();
        fragment = ctx.fragment();

        log = new DefaultRequestLog(this, options.requestContentPreviewerFactory(),
                                    options.responseContentPreviewerFactory());

        writeTimeoutMillis = ctx.writeTimeoutMillis();
        responseTimeoutMillis = ctx.responseTimeoutMillis();
        maxResponseLength = ctx.maxResponseLength();

        final HttpHeaders additionalHeaders = ctx.additionalRequestHeaders();
        if (!additionalHeaders.isEmpty()) {
            additionalRequestHeaders = additionalHeaders;
        }

        for (final Iterator<Attribute<?>> i = ctx.attrs(); i.hasNext();) {
            addAttr(i.next());
        }
        runThreadLocalContextCustomizer();
    }

    @SuppressWarnings("unchecked")
    private <T> void addAttr(Attribute<?> attribute) {
        final Attribute<T> a = (Attribute<T>) attribute;
        attr(a.key()).set(a.get());
    }

    @Override
    public ClientRequestContext newDerivedContext() {
        return new DefaultClientRequestContext(this);
    }

    @Override
    public ClientRequestContext newDerivedContext(Request request) {
        return new DefaultClientRequestContext(this, request);
    }

    @Override
    @Nullable
    protected Channel channel() {
        if (log.isAvailable(RequestLogAvailability.REQUEST_START)) {
            return log.channel();
        } else {
            return null;
        }
    }

    @Override
    public EventLoop eventLoop() {
        return eventLoop;
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        if (log.isAvailable(RequestLogAvailability.REQUEST_START)) {
            return log.sslSession();
        } else {
            return null;
        }
    }

    @Override
    public ClientOptions options() {
        return options;
    }

    @Override
    public Endpoint endpoint() {
        return endpoint;
    }

    @Override
    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
    }

    @Override
    @Nullable
    public String fragment() {
        return fragment;
    }

    @Override
    public long writeTimeoutMillis() {
        return writeTimeoutMillis;
    }

    @Override
    public void setWriteTimeoutMillis(long writeTimeoutMillis) {
        if (writeTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "writeTimeoutMillis: " + writeTimeoutMillis + " (expected: >= 0)");
        }
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    @Override
    public void setWriteTimeout(Duration writeTimeout) {
        setWriteTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    @Override
    public long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    @Override
    public void setResponseTimeoutMillis(long responseTimeoutMillis) {
        if (responseTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "responseTimeoutMillis: " + responseTimeoutMillis + " (expected: >= 0)");
        }
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    @Override
    public void setResponseTimeout(Duration responseTimeout) {
        setResponseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    @Override
    public long maxResponseLength() {
        return maxResponseLength;
    }

    @Override
    public void setMaxResponseLength(long maxResponseLength) {
        this.maxResponseLength = maxResponseLength;
    }

    @Override
    public HttpHeaders additionalRequestHeaders() {
        final HttpHeaders additionalRequestHeaders = this.additionalRequestHeaders;
        if (additionalRequestHeaders == null) {
            return HttpHeaders.of();
        }
        return additionalRequestHeaders;
    }

    @Override
    public void setAdditionalRequestHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        additionalRequestHeaders = createAdditionalHeadersIfAbsent().toBuilder().setObject(name, value).build();
    }

    @Override
    public void setAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        additionalRequestHeaders = createAdditionalHeadersIfAbsent().toBuilder().setObject(headers).build();
    }

    @Override
    public void addAdditionalRequestHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        additionalRequestHeaders = createAdditionalHeadersIfAbsent().toBuilder().addObject(name, value).build();
    }

    @Override
    public void addAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        additionalRequestHeaders = createAdditionalHeadersIfAbsent().toBuilder().addObject(headers).build();
    }

    @Override
    public boolean removeAdditionalRequestHeader(CharSequence name) {
        requireNonNull(name, "name");
        final HttpHeaders additionalRequestHeaders = this.additionalRequestHeaders;
        if (additionalRequestHeaders == null || additionalRequestHeaders.isEmpty()) {
            return false;
        }

        final HttpHeadersBuilder builder = additionalRequestHeaders.toBuilder();
        final boolean removed = builder.remove(name);
        this.additionalRequestHeaders = builder.build();
        return removed;
    }

    @Override
    public RequestLog log() {
        return log;
    }

    @Override
    public RequestLogBuilder logBuilder() {
        return log;
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal != null) {
            return strVal;
        }

        final StringBuilder buf = new StringBuilder(96);

        // Prepend the current channel information if available.
        final Channel ch = channel();
        final boolean hasChannel = ch != null;
        if (hasChannel) {
            buf.append(ch);
        }

        buf.append('[')
           .append(sessionProtocol().uriText())
           .append("://")
           .append(endpoint.authority())
           .append(path())
           .append('#')
           .append(method())
           .append(']');

        strVal = buf.toString();

        if (hasChannel) {
            this.strVal = strVal;
        }

        return strVal;
    }

    @Override
    public ByteBufAllocator alloc() {
        final Channel channel = channel();
        return channel != null ? channel.alloc() : PooledByteBufAllocator.DEFAULT;
    }
}
