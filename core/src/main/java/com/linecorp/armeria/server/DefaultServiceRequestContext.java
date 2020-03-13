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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.UnstableApi;
import com.linecorp.armeria.internal.common.TimeoutController;
import com.linecorp.armeria.internal.common.TimeoutScheduler;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;

/**
 * Default {@link ServiceRequestContext} implementation.
 */
@UnstableApi
public final class DefaultServiceRequestContext
        extends NonWrappingRequestContext
        implements ServiceRequestContext {

    private static final AtomicReferenceFieldUpdater<DefaultServiceRequestContext, HttpHeaders>
            additionalResponseHeadersUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultServiceRequestContext.class, HttpHeaders.class, "additionalResponseHeaders");

    private static final AtomicReferenceFieldUpdater<DefaultServiceRequestContext, HttpHeaders>
            additionalResponseTrailersUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultServiceRequestContext.class, HttpHeaders.class, "additionalResponseTrailers");

    private final Channel ch;
    private final ServiceConfig cfg;
    private final RoutingContext routingContext;
    private final RoutingResult routingResult;
    private final TimeoutScheduler timeoutScheduler;
    @Nullable
    private final SSLSession sslSession;

    private final ProxiedAddresses proxiedAddresses;

    private final InetAddress clientAddress;

    private final RequestLogBuilder log;

    @Nullable
    private ScheduledExecutorService blockingTaskExecutor;
    @Nullable
    private Runnable requestTimeoutHandler;
    private long maxRequestLength;

    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalResponseHeadersUpdater`
    private volatile HttpHeaders additionalResponseHeaders;
    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalResponseTrailersUpdater`
    private volatile HttpHeaders additionalResponseTrailers;

    @Nullable
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param cfg the {@link ServiceConfig}
     * @param ch the {@link Channel} that handles the invocation
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param id the {@link RequestId} that represents the identifier of the current {@link Request}
     *           and {@link Response} pair.
     * @param routingContext the parameters which are used when finding a matched {@link Route}
     * @param routingResult the result of finding a matched {@link Route}
     * @param req the request associated with this context
     * @param sslSession the {@link SSLSession} for this invocation if it is over TLS
     * @param proxiedAddresses source and destination addresses delivered through proxy servers
     * @param clientAddress the address of a client who initiated the request
     * @param requestStartTimeNanos {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch,
     *                               e.g. {@code System.currentTimeMillis() * 1000}.
     */
    public DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, RoutingContext routingContext, RoutingResult routingResult, HttpRequest req,
            @Nullable SSLSession sslSession, ProxiedAddresses proxiedAddresses, InetAddress clientAddress,
            long requestStartTimeNanos, long requestStartTimeMicros) {

        this(cfg, ch, meterRegistry, sessionProtocol, id, routingContext, routingResult, req,
             sslSession, proxiedAddresses, clientAddress, requestStartTimeNanos, requestStartTimeMicros,
             HttpHeaders.of(), HttpHeaders.of());
    }

    private DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, RoutingContext routingContext, RoutingResult routingResult, HttpRequest req,
            @Nullable SSLSession sslSession, ProxiedAddresses proxiedAddresses, InetAddress clientAddress,
            long requestStartTimeNanos, long requestStartTimeMicros,
            HttpHeaders additionalResponseHeaders, HttpHeaders additionalResponseTrailers) {

        super(meterRegistry, sessionProtocol, id,
              requireNonNull(routingContext, "routingContext").method(), routingContext.path(),
              requireNonNull(routingResult, "routingResult").query(),
              requireNonNull(req, "req"), null, null);

        this.ch = requireNonNull(ch, "ch");
        this.cfg = requireNonNull(cfg, "cfg");
        this.routingContext = routingContext;
        this.routingResult = routingResult;
        timeoutScheduler = new TimeoutScheduler(cfg.requestTimeoutMillis());
        this.sslSession = sslSession;
        this.proxiedAddresses = requireNonNull(proxiedAddresses, "proxiedAddresses");
        this.clientAddress = requireNonNull(clientAddress, "clientAddress");

        log = RequestLog.builder(this);
        log.startRequest(requestStartTimeNanos, requestStartTimeMicros);
        log.session(ch, sessionProtocol, sslSession, null);
        log.requestHeaders(req.headers());

        // For the server, request headers are processed well before ServiceRequestContext is created. It means
        // there is some delay between the actual channel read and this logging, but it's the best we can do for
        // now.
        log.requestFirstBytesTransferred();

        maxRequestLength = cfg.maxRequestLength();
        this.additionalResponseHeaders = additionalResponseHeaders;
        this.additionalResponseTrailers = additionalResponseTrailers;
    }

    @Nonnull
    @Override
    public <A extends SocketAddress> A remoteAddress() {
        final Channel ch = channel();
        assert ch != null;
        @SuppressWarnings("unchecked")
        final A addr = (A) ch.remoteAddress();
        return addr;
    }

    @Nonnull
    @Override
    public <A extends SocketAddress> A localAddress() {
        final Channel ch = channel();
        assert ch != null;
        @SuppressWarnings("unchecked")
        final A addr = (A) ch.localAddress();
        return addr;
    }

    @Override
    public InetAddress clientAddress() {
        return clientAddress;
    }

    @Override
    public ServiceRequestContext newDerivedContext(RequestId id,
                                                   @Nullable HttpRequest req,
                                                   @Nullable RpcRequest rpcReq) {
        requireNonNull(req, "req");
        if (rpcRequest() != null) {
            requireNonNull(rpcReq, "rpcReq");
        }

        final DefaultServiceRequestContext ctx = new DefaultServiceRequestContext(
                cfg, ch, meterRegistry(), sessionProtocol(), id, routingContext,
                routingResult, req, sslSession(), proxiedAddresses(), clientAddress(),
                System.nanoTime(), SystemInfo.currentTimeMicros(),
                additionalResponseHeaders, additionalResponseTrailers);

        if (rpcReq != null) {
            ctx.updateRpcRequest(rpcReq);
        }

        for (final Iterator<Entry<AttributeKey<?>, Object>> i = attrs(); i.hasNext();/* noop */) {
            ctx.addAttr(i.next());
        }

        return ctx;
    }

    @SuppressWarnings("unchecked")
    private <T> void addAttr(Entry<AttributeKey<?>, Object> attribute) {
        setAttr((AttributeKey<T>) attribute.getKey(), (T) attribute.getValue());
    }

    @Override
    protected Channel channel() {
        return ch;
    }

    @Override
    public Server server() {
        return cfg.server();
    }

    @Override
    public VirtualHost virtualHost() {
        return cfg.virtualHost();
    }

    @Override
    public Route route() {
        return cfg.route();
    }

    @Override
    public RoutingContext routingContext() {
        return routingContext;
    }

    @Override
    public Map<String, String> pathParams() {
        return routingResult.pathParams();
    }

    @Override
    public HttpService service() {
        return cfg.service();
    }

    @Override
    public ScheduledExecutorService blockingTaskExecutor() {
        if (blockingTaskExecutor != null) {
            return blockingTaskExecutor;
        }

        return blockingTaskExecutor = makeContextAware(server().config().blockingTaskExecutor());
    }

    @Override
    public String mappedPath() {
        return routingResult.path();
    }

    @Override
    public String decodedMappedPath() {
        return routingResult.decodedPath();
    }

    @Nullable
    @Override
    public MediaType negotiatedResponseMediaType() {
        return routingResult.negotiatedResponseMediaType();
    }

    @Override
    public EventLoop eventLoop() {
        return ch.eventLoop();
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        return sslSession;
    }

    @Override
    public long requestTimeoutMillis() {
        return timeoutScheduler.timeoutMillis();
    }

    @Override
    public void clearRequestTimeout() {
        timeoutScheduler.clearTimeout();
    }

    @Override
    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        timeoutScheduler.setTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public void setRequestTimeout(Duration requestTimeout) {
        setRequestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Override
    public void extendRequestTimeoutMillis(long adjustmentMillis) {
        timeoutScheduler.extendTimeoutMillis(adjustmentMillis);
    }

    @Override
    public void extendRequestTimeout(Duration adjustment) {
        extendRequestTimeoutMillis(requireNonNull(adjustment, "adjustment").toMillis());
    }

    @Override
    public void setRequestTimeoutAfterMillis(long requestTimeoutMillis) {
        timeoutScheduler.setTimeoutAfterMillis(requestTimeoutMillis);
    }

    @Override
    public void setRequestTimeoutAfter(Duration requestTimeout) {
        setRequestTimeoutAfterMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Override
    public void setRequestTimeoutAtMillis(long requestTimeoutAtMillis) {
        timeoutScheduler.setTimeoutAtMillis(requestTimeoutAtMillis);
    }

    @Override
    public void setRequestTimeoutAt(Instant requestTimeoutAt) {
        setRequestTimeoutAtMillis(requireNonNull(requestTimeoutAt, "requestTimeoutAt").toEpochMilli());
    }

    @Nullable
    @Override
    public Runnable requestTimeoutHandler() {
        return requestTimeoutHandler;
    }

    @Override
    public void setRequestTimeoutHandler(Runnable requestTimeoutHandler) {
        this.requestTimeoutHandler = requireNonNull(requestTimeoutHandler, "requestTimeoutHandler");
    }

    @Override
    public boolean isTimedOut() {
        return timeoutScheduler.isTimedOut();
    }

    @Override
    public long maxRequestLength() {
        return maxRequestLength;
    }

    @Override
    public void setMaxRequestLength(long maxRequestLength) {
        checkArgument(maxRequestLength >= 0, "maxRequestLength: %s (expected: >= 0)", maxRequestLength);
        this.maxRequestLength = maxRequestLength;
    }

    @Override
    public boolean verboseResponses() {
        return cfg.verboseResponses();
    }

    @Override
    public AccessLogWriter accessLogWriter() {
        return cfg.accessLogWriter();
    }

    @Override
    public HttpHeaders additionalResponseHeaders() {
        return additionalResponseHeaders;
    }

    @Override
    public void setAdditionalResponseHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        mutateAdditionalResponseHeaders(additionalResponseHeadersUpdater,
                                        builder -> builder.setObject(name, value));
    }

    @Override
    public void addAdditionalResponseHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        mutateAdditionalResponseHeaders(additionalResponseHeadersUpdater,
                                        builder -> builder.addObject(name, value));
    }

    @Override
    public void mutateAdditionalResponseHeaders(Consumer<HttpHeadersBuilder> mutator) {
        requireNonNull(mutator, "mutator");
        mutateAdditionalResponseHeaders(additionalResponseHeadersUpdater, mutator);
    }

    private void mutateAdditionalResponseHeaders(
            AtomicReferenceFieldUpdater<DefaultServiceRequestContext, HttpHeaders> atomicUpdater,
            Consumer<HttpHeadersBuilder> mutator) {
        for (;;) {
            final HttpHeaders oldValue = atomicUpdater.get(this);
            final HttpHeadersBuilder builder = oldValue.toBuilder();
            mutator.accept(builder);
            final HttpHeaders newValue = builder.build();
            if (atomicUpdater.compareAndSet(this, oldValue, newValue)) {
                return;
            }
        }
    }

    @Override
    public HttpHeaders additionalResponseTrailers() {
        return additionalResponseTrailers;
    }

    @Override
    public void setAdditionalResponseTrailer(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        mutateAdditionalResponseHeaders(additionalResponseTrailersUpdater,
                                        builder -> builder.setObject(name, value));
    }

    @Override
    public void addAdditionalResponseTrailer(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        mutateAdditionalResponseHeaders(additionalResponseTrailersUpdater,
                                        builder -> builder.addObject(name, value));
    }

    @Override
    public void mutateAdditionalResponseTrailers(Consumer<HttpHeadersBuilder> mutator) {
        requireNonNull(mutator, "mutator");
        mutateAdditionalResponseHeaders(additionalResponseTrailersUpdater, mutator);
    }

    @Override
    public ProxiedAddresses proxiedAddresses() {
        return proxiedAddresses;
    }

    @Override
    public RequestLogAccess log() {
        return log;
    }

    @Override
    public RequestLogBuilder logBuilder() {
        return log;
    }

    @Override
    public ByteBufAllocator alloc() {
        return ch.alloc();
    }

    /**
     * Sets the {@code requestTimeoutController} that is set to a new timeout when
     * the {@linkplain #requestTimeoutMillis()} request timeout} of the request is changed.
     *
     * <p>Note: This method is meant for internal use by server-side protocol implementation to reschedule
     * a timeout task when a user updates the request timeout configuration.
     */
    void setRequestTimeoutController(TimeoutController requestTimeoutController) {
        timeoutScheduler.setTimeoutController(requestTimeoutController, eventLoop());
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal != null) {
            return strVal;
        }

        final StringBuilder buf = new StringBuilder(108);
        buf.append("[S]");

        // Prepend the current channel information if available.
        final Channel ch = channel();
        final boolean hasChannel = ch != null;
        if (hasChannel) {
            buf.append(ch);

            final InetAddress remote = ((InetSocketAddress) remoteAddress()).getAddress();
            final InetAddress client = clientAddress();
            if (remote != null && !remote.equals(client)) {
                buf.append("[C:").append(client.getHostAddress()).append(']');
            }
        }

        buf.append('[')
           .append(sessionProtocol().uriText())
           .append("://")
           .append(virtualHost().defaultHostname());

        final InetSocketAddress laddr = localAddress();
        if (laddr != null) {
            buf.append(':').append(laddr.getPort());
        } else {
            buf.append(":-1"); // Port unknown.
        }

        buf.append(path())
           .append('#')
           .append(method())
           .append(']');

        strVal = buf.toString();
        if (hasChannel) {
            this.strVal = strVal;
        }
        return strVal;
    }
}
