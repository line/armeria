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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;
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
    private final PathMappingContext pathMappingContext;
    private final PathMappingResult pathMappingResult;
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

    @Nullable
    private volatile RequestTimeoutChangeListener requestTimeoutChangeListener;
    @Nullable
    @SuppressWarnings("unused")
    private volatile HttpHeaders additionalResponseHeaders; // set only via additionalResponseHeadersUpdater
    @Nullable
    @SuppressWarnings("unused")
    private volatile HttpHeaders additionalResponseTrailers; // set only via additionalResponseTrailersUpdater

    @Nullable
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param cfg the {@link ServiceConfig}
     * @param ch the {@link Channel} that handles the invocation
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param pathMappingContext the parameters which are used when finding a matched {@link PathMapping}
     * @param pathMappingResult the result of finding a matched {@link PathMapping}
     * @param request the request associated with this context
     * @param sslSession the {@link SSLSession} for this invocation if it is over TLS
     * @param proxiedAddresses source and destination addresses retrieved from PROXY protocol header
     * @param clientAddress the address of a client who initiated the request
     */
    public DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            PathMappingContext pathMappingContext, PathMappingResult pathMappingResult, HttpRequest request,
            @Nullable SSLSession sslSession, @Nullable ProxiedAddresses proxiedAddresses,
            InetAddress clientAddress) {
        this(cfg, ch, meterRegistry, sessionProtocol, pathMappingContext, pathMappingResult, request,
             sslSession, proxiedAddresses, clientAddress, false, 0, 0);
    }

    /**
     * Creates a new instance.
     *
     * @param cfg the {@link ServiceConfig}
     * @param ch the {@link Channel} that handles the invocation
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param pathMappingContext the parameters which are used when finding a matched {@link PathMapping}
     * @param pathMappingResult the result of finding a matched {@link PathMapping}
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
            PathMappingContext pathMappingContext, PathMappingResult pathMappingResult, HttpRequest request,
            @Nullable SSLSession sslSession, @Nullable ProxiedAddresses proxiedAddresses,
            InetAddress clientAddress, long requestStartTimeNanos, long requestStartTimeMicros) {
        this(cfg, ch, meterRegistry, sessionProtocol, pathMappingContext, pathMappingResult, request,
             sslSession, proxiedAddresses, clientAddress, true, requestStartTimeNanos, requestStartTimeMicros);
    }

    private DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            PathMappingContext pathMappingContext, PathMappingResult pathMappingResult, HttpRequest request,
            @Nullable SSLSession sslSession, @Nullable ProxiedAddresses proxiedAddresses,
            InetAddress clientAddress, boolean requestStartTimeSet, long requestStartTimeNanos,
            long requestStartTimeMicros) {

        super(meterRegistry, sessionProtocol,
              requireNonNull(pathMappingContext, "pathMappingContext").method(), pathMappingContext.path(),
              requireNonNull(pathMappingResult, "pathMappingResult").query(),
              request);

        this.ch = requireNonNull(ch, "ch");
        this.cfg = requireNonNull(cfg, "cfg");
        this.pathMappingContext = pathMappingContext;
        this.pathMappingResult = pathMappingResult;
        this.sslSession = sslSession;
        this.proxiedAddresses = proxiedAddresses;
        this.clientAddress = requireNonNull(clientAddress, "clientAddress");

        log = new DefaultRequestLog(this);
        if (requestStartTimeSet) {
            log.startRequest(ch, sessionProtocol, sslSession, requestStartTimeNanos, requestStartTimeMicros);
        } else {
            log.startRequest(ch, sessionProtocol, sslSession);
        }
        log.requestHeaders(request.headers());

        // For the server, request headers are processed well before ServiceRequestContext is created. It means
        // there is some delay between the actual channel read and this logging, but it's the best we can do for
        // now.
        log.requestFirstBytesTransferred();

        logger = newLogger(cfg);

        final ServerConfig serverCfg = cfg.server().config();
        requestTimeoutMillis = serverCfg.defaultRequestTimeoutMillis();
        maxRequestLength = serverCfg.defaultMaxRequestLength();
    }

    private RequestContextAwareLogger newLogger(ServiceConfig cfg) {
        String loggerName = cfg.loggerName().orElse(null);
        if (loggerName == null) {
            loggerName = cfg.pathMapping().loggerName();
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
    public ServiceRequestContext newDerivedContext() {
        return newDerivedContext(request());
    }

    @Override
    public ServiceRequestContext newDerivedContext(Request request) {
        final DefaultServiceRequestContext ctx = new DefaultServiceRequestContext(
                cfg, ch, meterRegistry(), sessionProtocol(), pathMappingContext,
                pathMappingResult, (HttpRequest) request, sslSession(), proxiedAddresses(), clientAddress);

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

    private HttpHeaders createAdditionalHeadersIfAbsent() {
        final HttpHeaders additionalResponseHeaders = this.additionalResponseHeaders;
        if (additionalResponseHeaders == null) {
            final HttpHeaders newHeaders = new DefaultHttpHeaders();
            if (additionalResponseHeadersUpdater.compareAndSet(this, null, newHeaders)) {
                return newHeaders;
            } else {
                final HttpHeaders additionalResponseHeadersSetByOtherThread = this.additionalResponseHeaders;
                assert additionalResponseHeadersSetByOtherThread != null;
                return additionalResponseHeadersSetByOtherThread;
            }
        } else {
            return additionalResponseHeaders;
        }
    }

    private HttpHeaders createAdditionalTrailersIfAbsent() {
        final HttpHeaders additionalResponseTrailers = this.additionalResponseTrailers;
        if (additionalResponseTrailers == null) {
            final HttpHeaders newTrailers = new DefaultHttpHeaders();
            if (additionalResponseTrailersUpdater.compareAndSet(this, null, newTrailers)) {
                return newTrailers;
            } else {
                final HttpHeaders additionalResponseTrailersSetByOtherThread = this.additionalResponseTrailers;
                assert additionalResponseTrailersSetByOtherThread != null;
                return additionalResponseTrailersSetByOtherThread;
            }
        } else {
            return additionalResponseTrailers;
        }
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
    public PathMapping pathMapping() {
        return cfg.pathMapping();
    }

    @Override
    public PathMappingContext pathMappingContext() {
        return pathMappingContext;
    }

    @Override
    public Map<String, String> pathParams() {
        return pathMappingResult.pathParams();
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>> T service() {
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
        return pathMappingResult.path();
    }

    @Override
    public String decodedMappedPath() {
        return pathMappingResult.decodedPath();
    }

    @Nullable
    @Override
    public MediaType negotiatedResponseMediaType() {
        return pathMappingResult.negotiatedResponseMediaType();
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
    public HttpHeaders additionalResponseHeaders() {
        final HttpHeaders additionalResponseHeaders = this.additionalResponseHeaders;
        if (additionalResponseHeaders == null) {
            return HttpHeaders.EMPTY_HEADERS;
        }
        return additionalResponseHeaders.asImmutable();
    }

    @Override
    public void setAdditionalResponseHeader(AsciiString name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        createAdditionalHeadersIfAbsent().set(name, value);
    }

    @Override
    public void setAdditionalResponseHeaders(Headers<? extends AsciiString, ? extends String, ?> headers) {
        requireNonNull(headers, "headers");
        createAdditionalHeadersIfAbsent().set(headers);
    }

    @Override
    public void addAdditionalResponseHeader(AsciiString name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        createAdditionalHeadersIfAbsent().add(name, value);
    }

    @Override
    public void addAdditionalResponseHeaders(Headers<? extends AsciiString, ? extends String, ?> headers) {
        requireNonNull(headers, "headers");
        createAdditionalHeadersIfAbsent().add(headers);
    }

    @Override
    public boolean removeAdditionalResponseHeader(AsciiString name) {
        requireNonNull(name, "name");
        return createAdditionalHeadersIfAbsent().remove(name);
    }

    @Override
    public HttpHeaders additionalResponseTrailers() {
        final HttpHeaders additionalResponseTrailers = this.additionalResponseTrailers;
        if (additionalResponseTrailers == null) {
            return HttpHeaders.EMPTY_HEADERS;
        }
        return additionalResponseTrailers.asImmutable();
    }

    @Override
    public void setAdditionalResponseTrailer(AsciiString name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        createAdditionalTrailersIfAbsent().set(name, value);
    }

    @Override
    public void setAdditionalResponseTrailers(Headers<? extends AsciiString, ? extends String, ?> headers) {
        requireNonNull(headers, "headers");
        createAdditionalTrailersIfAbsent().set(headers);
    }

    @Override
    public void addAdditionalResponseTrailer(AsciiString name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        createAdditionalTrailersIfAbsent().add(name, value);
    }

    @Override
    public void addAdditionalResponseTrailers(Headers<? extends AsciiString, ? extends String, ?> headers) {
        requireNonNull(headers, "headers");
        createAdditionalTrailersIfAbsent().add(headers);
    }

    @Override
    public boolean removeAdditionalResponseTrailer(AsciiString name) {
        requireNonNull(name, "name");
        return createAdditionalTrailersIfAbsent().remove(name);
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
