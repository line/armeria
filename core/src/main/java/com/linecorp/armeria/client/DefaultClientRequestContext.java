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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
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
import com.linecorp.armeria.common.stream.StreamMessage;

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

    private static final AtomicReferenceFieldUpdater<DefaultClientRequestContext, HttpHeaders>
            additionalRequestHeadersUpdater = AtomicReferenceFieldUpdater.newUpdater(
                    DefaultClientRequestContext.class, HttpHeaders.class, "additionalRequestHeaders");

    private final EventLoop eventLoop;
    private final ClientOptions options;
    @Nullable
    private EndpointSelector endpointSelector;
    @Nullable
    private Endpoint endpoint;
    @Nullable
    private final String fragment;

    private final DefaultRequestLog log;

    private long writeTimeoutMillis;
    private long responseTimeoutMillis;
    private long maxResponseLength;

    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalRequestHeadersUpdater`
    private volatile HttpHeaders additionalRequestHeaders;

    @Nullable
    private String strVal;

    /**
     * Creates a new instance. Note that {@link #init(Endpoint)} method must be invoked to finish
     * the construction of this context.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param request the request associated with this context
     */
    public DefaultClientRequestContext(
            EventLoop eventLoop, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
            ClientOptions options, Request request) {

        super(meterRegistry, sessionProtocol, method, path, query, request);

        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.options = requireNonNull(options, "options");
        this.fragment = fragment;

        log = new DefaultRequestLog(this, options.requestContentPreviewerFactory(),
                                    options.responseContentPreviewerFactory());

        writeTimeoutMillis = options.writeTimeoutMillis();
        responseTimeoutMillis = options.responseTimeoutMillis();
        maxResponseLength = options.maxResponseLength();
        additionalRequestHeaders = options.getOrElse(ClientOption.HTTP_HEADERS, HttpHeaders.of());
    }

    /**
     * Initializes this context with the specified {@link Endpoint}.
     * This method must be invoked to finish the construction of this context.
     *
     * @return {@code true} if the initialization has succeeded.
     *         {@code false} if the initialization has failed and this context's {@link RequestLog} has been
     *         completed with the cause of the failure.
     */
    public boolean init(Endpoint endpoint) {
        assert this.endpoint == null : this.endpoint;
        try {
            if (endpoint.isGroup()) {
                final String groupName = endpoint.groupName();
                final EndpointSelector endpointSelector =
                        EndpointGroupRegistry.getNodeSelector(groupName);
                if (endpointSelector == null) {
                    throw new EndpointGroupException(
                            "non-existent " + EndpointGroup.class.getSimpleName() + ": " + groupName);
                }

                this.endpointSelector = endpointSelector;
                // Note: thread-local customizer must be run before EndpointSelector.select()
                //       so that the customizer can inject the attributes which may be required
                //       by the EndpointSelector.
                runThreadLocalContextCustomizer();
                this.endpoint = endpointSelector.select(this);
            } else {
                endpointSelector = null;
                this.endpoint = endpoint;
                runThreadLocalContextCustomizer();
            }

            return true;
        } catch (Exception e) {
            failEarly(e);
        }

        return false;
    }

    private void runThreadLocalContextCustomizer() {
        final Consumer<ClientRequestContext> customizer = THREAD_LOCAL_CONTEXT_CUSTOMIZER.get();
        if (customizer != null) {
            customizer.accept(this);
        }
    }

    private void failEarly(Exception cause) {
        final RequestLogBuilder logBuilder = logBuilder();
        final UnprocessedRequestException wrapped = new UnprocessedRequestException(cause);
        logBuilder.endRequest(wrapped);
        logBuilder.endResponse(wrapped);

        final Request req = request();
        if (req instanceof StreamMessage) {
            ((StreamMessage<?>) req).abort();
        }
    }

    private DefaultClientRequestContext(DefaultClientRequestContext ctx, Request request, Endpoint endpoint) {
        super(ctx.meterRegistry(), ctx.sessionProtocol(), ctx.method(), ctx.path(), ctx.query(), request);

        eventLoop = ctx.eventLoop();
        options = ctx.options();
        endpointSelector = ctx.endpointSelector();
        this.endpoint = requireNonNull(endpoint, "endpoint");
        fragment = ctx.fragment();

        log = new DefaultRequestLog(this, options.requestContentPreviewerFactory(),
                                    options.responseContentPreviewerFactory());

        writeTimeoutMillis = ctx.writeTimeoutMillis();
        responseTimeoutMillis = ctx.responseTimeoutMillis();
        maxResponseLength = ctx.maxResponseLength();
        additionalRequestHeaders = ctx.additionalRequestHeaders();

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
        return newDerivedContext(request());
    }

    @Override
    public ClientRequestContext newDerivedContext(Request request) {
        checkState(endpoint != null, "endpoint not available");
        return newDerivedContext(request, endpoint);
    }

    @Override
    public ClientRequestContext newDerivedContext(Request request, Endpoint endpoint) {
        return new DefaultClientRequestContext(this, request, endpoint);
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
    public EndpointSelector endpointSelector() {
        return endpointSelector;
    }

    @Override
    public Endpoint endpoint() {
        return endpoint;
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
        return additionalRequestHeaders;
    }

    @Override
    public void setAdditionalRequestHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        updateAdditionalRequestHeaders(builder -> builder.setObject(name, value));
    }

    @Override
    public void setAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        updateAdditionalRequestHeaders(builder -> builder.setObject(headers));
    }

    @Override
    public void addAdditionalRequestHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        updateAdditionalRequestHeaders(builder -> builder.addObject(name, value));
    }

    @Override
    public void addAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        updateAdditionalRequestHeaders(builder -> builder.addObject(headers));
    }

    private void updateAdditionalRequestHeaders(Function<HttpHeadersBuilder, HttpHeadersBuilder> updater) {
        for (;;) {
            final HttpHeaders oldValue = additionalRequestHeaders;
            final HttpHeaders newValue = updater.apply(oldValue.toBuilder()).build();
            if (additionalRequestHeadersUpdater.compareAndSet(this, oldValue, newValue)) {
                return;
            }
        }
    }

    @Override
    public boolean removeAdditionalRequestHeader(CharSequence name) {
        requireNonNull(name, "name");
        for (;;) {
            final HttpHeaders oldValue = additionalRequestHeaders;
            if (oldValue.isEmpty() || !oldValue.contains(name)) {
                return false;
            }

            final HttpHeaders newValue = oldValue.toBuilder().removeAndThen(name).build();
            if (additionalRequestHeadersUpdater.compareAndSet(this, oldValue, newValue)) {
                return true;
            }
        }
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
           .append(endpoint != null ? endpoint.authority() : "UNKNOWN")
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
