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

import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.Attribute;

/**
 * Default {@link ServiceRequestContext} implementation.
 */
public class DefaultServiceRequestContext extends NonWrappingRequestContext implements ServiceRequestContext {

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
    @Nullable
    private final SSLSession sslSession;

    @Nullable
    private final ProxiedAddresses proxiedAddresses;
    private final InetAddress clientAddress;

    private final DefaultRequestLog log;
    private final Logger logger;

    @Nullable
    private ExecutorService blockingTaskExecutor;

    private long requestTimeoutMillis;
    @Nullable
    private Runnable requestTimeoutHandler;
    private long maxRequestLength;

    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalResponseHeadersUpdater`
    private volatile HttpHeaders additionalResponseHeaders;
    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalResponseTrailersUpdater`
    private volatile HttpHeaders additionalResponseTrailers;

    @Nullable
    private volatile RequestTimeoutChangeListener requestTimeoutChangeListener;

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
     * @param request the request associated with this context
     * @param sslSession the {@link SSLSession} for this invocation if it is over TLS
     * @param proxiedAddresses source and destination addresses retrieved from PROXY protocol header
     * @param clientAddress the address of a client who initiated the request
     */
    public DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, RoutingContext routingContext, RoutingResult routingResult, HttpRequest request,
            @Nullable SSLSession sslSession, @Nullable ProxiedAddresses proxiedAddresses,
            InetAddress clientAddress) {
        this(cfg, ch, meterRegistry, sessionProtocol, id, routingContext, routingResult, request,
             sslSession, proxiedAddresses, clientAddress, false, 0, 0);
    }

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
     * @param request the request associated with this context
     * @param sslSession the {@link SSLSession} for this invocation if it is over TLS
     * @param proxiedAddresses source and destination addresses retrieved from PROXY protocol header
     * @param clientAddress the address of a client who initiated the request
     * @param requestStartTimeNanos {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch,
     *                               e.g. {@code System.currentTimeMillis() * 1000}.
     */
    public DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, RoutingContext routingContext, RoutingResult routingResult, HttpRequest request,
            @Nullable SSLSession sslSession, @Nullable ProxiedAddresses proxiedAddresses,
            InetAddress clientAddress, long requestStartTimeNanos, long requestStartTimeMicros) {
        this(cfg, ch, meterRegistry, sessionProtocol, id, routingContext, routingResult, request,
             sslSession, proxiedAddresses, clientAddress, true, requestStartTimeNanos, requestStartTimeMicros);
    }

    private DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, RoutingContext routingContext, RoutingResult routingResult, HttpRequest req,
            @Nullable SSLSession sslSession, @Nullable ProxiedAddresses proxiedAddresses,
            InetAddress clientAddress, boolean requestStartTimeSet, long requestStartTimeNanos,
            long requestStartTimeMicros) {

        super(meterRegistry, sessionProtocol, id,
              requireNonNull(routingContext, "routingContext").method(), routingContext.path(),
              requireNonNull(routingResult, "routingResult").query(),
              requireNonNull(req, "req"), null);

        this.ch = requireNonNull(ch, "ch");
        this.cfg = requireNonNull(cfg, "cfg");
        this.routingContext = routingContext;
        this.routingResult = routingResult;
        this.sslSession = sslSession;
        this.proxiedAddresses = proxiedAddresses;
        this.clientAddress = requireNonNull(clientAddress, "clientAddress");

        log = new DefaultRequestLog(this, cfg.requestContentPreviewerFactory(),
                                    cfg.responseContentPreviewerFactory());
        if (requestStartTimeSet) {
            log.startRequest(ch, sessionProtocol, sslSession, requestStartTimeNanos, requestStartTimeMicros);
        } else {
            log.startRequest(ch, sessionProtocol, sslSession);
        }
        log.requestHeaders(req.headers());

        // For the server, request headers are processed well before ServiceRequestContext is created. It means
        // there is some delay between the actual channel read and this logging, but it's the best we can do for
        // now.
        log.requestFirstBytesTransferred();

        logger = newLogger(cfg);

        requestTimeoutMillis = cfg.requestTimeoutMillis();
        maxRequestLength = cfg.maxRequestLength();
        additionalResponseHeaders = HttpHeaders.of();
        additionalResponseTrailers = HttpHeaders.of();
    }

    private RequestContextAwareLogger newLogger(ServiceConfig cfg) {
        String loggerName = cfg.loggerName().orElse(null);
        if (loggerName == null) {
            loggerName = cfg.route().loggerName();
        }

        return new RequestContextAwareLogger(this, LoggerFactory.getLogger(
                cfg.server().config().serviceLoggerPrefix() + '.' + loggerName));
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
                routingResult, req, sslSession(), proxiedAddresses(), clientAddress);

        if (rpcReq != null) {
            ctx.updateRpcRequest(rpcReq);
        }

        final HttpHeaders additionalHeaders = additionalResponseHeaders();
        if (!additionalHeaders.isEmpty()) {
            ctx.setAdditionalResponseHeaders(additionalHeaders);
        }

        final HttpHeaders additionalTrailers = additionalResponseTrailers();
        if (!additionalTrailers.isEmpty()) {
            ctx.setAdditionalResponseTrailers(additionalTrailers);
        }

        for (final Iterator<Attribute<?>> i = attrs(); i.hasNext();/* noop */) {
            ctx.addAttr(i.next());
        }
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private <T> void addAttr(Attribute<?> attribute) {
        final Attribute<T> a = (Attribute<T>) attribute;
        attr(a.key()).set(a.get());
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
    public <T extends HttpService> T service() {
        return cfg.service();
    }

    @Override
    public ExecutorService blockingTaskExecutor() {
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

    @Override
    public Logger logger() {
        return logger;
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        return sslSession;
    }

    @Override
    public long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    @Override
    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        if (requestTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutMillis: " + requestTimeoutMillis + " (expected: >= 0)");
        }
        if (this.requestTimeoutMillis != requestTimeoutMillis) {
            this.requestTimeoutMillis = requestTimeoutMillis;
            final RequestTimeoutChangeListener listener = requestTimeoutChangeListener;
            if (listener != null) {
                if (ch.eventLoop().inEventLoop()) {
                    listener.onRequestTimeoutChange(requestTimeoutMillis);
                } else {
                    ch.eventLoop().execute(() -> listener.onRequestTimeoutChange(requestTimeoutMillis));
                }
            }
        }
    }

    @Override
    public void setRequestTimeout(Duration requestTimeout) {
        setRequestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Nullable
    public Runnable requestTimeoutHandler() {
        return requestTimeoutHandler;
    }

    @Override
    public void setRequestTimeoutHandler(Runnable requestTimeoutHandler) {
        this.requestTimeoutHandler = requireNonNull(requestTimeoutHandler, "requestTimeoutHandler");
    }

    /**
     * Marks this {@link ServiceRequestContext} as having been timed out. Any callbacks created with
     * {@code makeContextAware} that are run after this will be failed with {@link CancellationException}.
     */
    @Override
    public void setTimedOut() {
        super.setTimedOut();
    }

    @Override
    public long maxRequestLength() {
        return maxRequestLength;
    }

    @Override
    public void setMaxRequestLength(long maxRequestLength) {
        if (maxRequestLength < 0) {
            throw new IllegalArgumentException(
                    "maxRequestLength: " + maxRequestLength + " (expected: >= 0)");
        }
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
        updateAdditionalResponseHeaders(additionalResponseHeadersUpdater,
                                        builder -> builder.setObject(name, value));
    }

    @Override
    public void setAdditionalResponseHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        updateAdditionalResponseHeaders(additionalResponseHeadersUpdater,
                                        builder -> builder.setObject(headers));
    }

    @Override
    public void addAdditionalResponseHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        updateAdditionalResponseHeaders(additionalResponseHeadersUpdater,
                                        builder -> builder.addObject(name, value));
    }

    @Override
    public void addAdditionalResponseHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        updateAdditionalResponseHeaders(additionalResponseHeadersUpdater,
                                        builder -> builder.addObject(headers));
    }

    private void updateAdditionalResponseHeaders(
            AtomicReferenceFieldUpdater<DefaultServiceRequestContext, HttpHeaders> atomicUpdater,
            Function<HttpHeadersBuilder, HttpHeadersBuilder> valueUpdater) {
        for (;;) {
            final HttpHeaders oldValue = atomicUpdater.get(this);
            final HttpHeaders newValue = valueUpdater.apply(oldValue.toBuilder()).build();
            if (atomicUpdater.compareAndSet(this, oldValue, newValue)) {
                return;
            }
        }
    }

    @Override
    public boolean removeAdditionalResponseHeader(CharSequence name) {
        return removeAdditionalResponseHeader(additionalResponseHeadersUpdater, name);
    }

    private boolean removeAdditionalResponseHeader(
            AtomicReferenceFieldUpdater<DefaultServiceRequestContext, HttpHeaders> atomicUpdater,
            CharSequence name) {
        requireNonNull(name, "name");
        for (;;) {
            final HttpHeaders oldValue = atomicUpdater.get(this);
            if (oldValue.isEmpty() || !oldValue.contains(name)) {
                return false;
            }

            final HttpHeaders newValue = oldValue.toBuilder().removeAndThen(name).build();
            if (atomicUpdater.compareAndSet(this, oldValue, newValue)) {
                return true;
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
        updateAdditionalResponseHeaders(additionalResponseTrailersUpdater,
                                        builder -> builder.setObject(name, value));
    }

    @Override
    public void setAdditionalResponseTrailers(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        updateAdditionalResponseHeaders(additionalResponseTrailersUpdater,
                                        builder -> builder.setObject(headers));
    }

    @Override
    public void addAdditionalResponseTrailer(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        updateAdditionalResponseHeaders(additionalResponseTrailersUpdater,
                                        builder -> builder.addObject(name, value));
    }

    @Override
    public void addAdditionalResponseTrailers(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        updateAdditionalResponseHeaders(additionalResponseTrailersUpdater,
                                        builder -> builder.addObject(headers));
    }

    @Override
    public boolean removeAdditionalResponseTrailer(CharSequence name) {
        return removeAdditionalResponseHeader(additionalResponseTrailersUpdater, name);
    }

    @Nullable
    @Override
    public ProxiedAddresses proxiedAddresses() {
        return proxiedAddresses;
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
    public ByteBufAllocator alloc() {
        return ch.alloc();
    }

    /**
     * Sets the listener that is notified when the {@linkplain #requestTimeoutMillis()} request timeout} of
     * the request is changed.
     *
     * <p>Note: This method is meant for internal use by server-side protocol implementation to reschedule
     * a timeout task when a user updates the request timeout configuration.
     */
    public void setRequestTimeoutChangeListener(RequestTimeoutChangeListener listener) {
        requireNonNull(listener, "listener");
        if (requestTimeoutChangeListener != null) {
            throw new IllegalStateException("requestTimeoutChangeListener is set already.");
        }
        requestTimeoutChangeListener = listener;
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
