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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.ContextAwareScheduledExecutorService;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.InitiateConnectionShutdown;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
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

    private static final InetSocketAddress UNKNOWN_ADDR = new InetSocketAddress("0.0.0.0", 1);

    private final Channel ch;
    private final ServiceConfig cfg;
    private final RoutingContext routingContext;
    private final RoutingResult routingResult;
    private final CancellationScheduler requestCancellationScheduler;
    @Nullable
    private final SSLSession sslSession;

    private final ProxiedAddresses proxiedAddresses;

    private final InetAddress clientAddress;

    private final RequestLogBuilder log;

    @Nullable
    private ContextAwareEventLoop contextAwareEventLoop;
    @Nullable
    private ContextAwareScheduledExecutorService blockingTaskExecutor;
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
             sslSession, proxiedAddresses, clientAddress, /* requestCancellationScheduler */ null,
             requestStartTimeNanos, requestStartTimeMicros, HttpHeaders.of(), HttpHeaders.of());
    }

    DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, RoutingContext routingContext, RoutingResult routingResult, HttpRequest req,
            @Nullable SSLSession sslSession, ProxiedAddresses proxiedAddresses, InetAddress clientAddress,
            @Nullable CancellationScheduler requestCancellationScheduler,
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
        if (requestCancellationScheduler != null) {
            this.requestCancellationScheduler = requestCancellationScheduler;
        } else {
            this.requestCancellationScheduler =
                    new CancellationScheduler(TimeUnit.MILLISECONDS.toNanos(cfg.requestTimeoutMillis()));
        }
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

    @Nullable
    @Override
    public <V> V attr(AttributeKey<V> key) {
        // Don't check the root attributes; root is always null.
        return ownAttr(key);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        // Don't check the root attributes; root is always null.
        return ownAttrs();
    }

    @Nonnull
    @Override
    public <A extends SocketAddress> A remoteAddress() {
        @SuppressWarnings("unchecked")
        final A addr = (A) firstNonNull(ch.remoteAddress(), UNKNOWN_ADDR);
        return addr;
    }

    @Nonnull
    @Override
    public <A extends SocketAddress> A localAddress() {
        @SuppressWarnings("unchecked")
        final A addr = (A) firstNonNull(ch.localAddress(), UNKNOWN_ADDR);
        return addr;
    }

    @Override
    public InetAddress clientAddress() {
        return clientAddress;
    }

    @Override
    protected Channel channel() {
        return ch;
    }

    @Override
    public ServiceConfig config() {
        return cfg;
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
    public ContextAwareScheduledExecutorService blockingTaskExecutor() {
        if (blockingTaskExecutor != null) {
            return blockingTaskExecutor;
        }

        return blockingTaskExecutor = ContextAwareScheduledExecutorService.of(
                this, config().server().config().blockingTaskExecutor());
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
    public ContextAwareEventLoop eventLoop() {
        if (contextAwareEventLoop != null) {
            return contextAwareEventLoop;
        }
        return contextAwareEventLoop = ContextAwareEventLoop.of(this, ch.eventLoop());
    }

    @Override
    public ByteBufAllocator alloc() {
        return ch.alloc();
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        return sslSession;
    }

    @Override
    public long requestTimeoutMillis() {
        return TimeUnit.NANOSECONDS.toMillis(requestCancellationScheduler.timeoutNanos());
    }

    @Override
    public void clearRequestTimeout() {
        requestCancellationScheduler.clearTimeout();
    }

    @Override
    public void setRequestTimeoutMillis(TimeoutMode mode, long requestTimeoutMillis) {
        requestCancellationScheduler.setTimeoutNanos(requireNonNull(mode, "mode"),
                                                     TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis));
    }

    @Override
    public void setRequestTimeout(TimeoutMode mode, Duration requestTimeout) {
        requestCancellationScheduler.setTimeoutNanos(requireNonNull(mode, "mode"),
                                                     requireNonNull(requestTimeout, "requestTimeout")
                                                             .toNanos());
    }

    CancellationScheduler requestCancellationScheduler() {
        return requestCancellationScheduler;
    }

    @Override
    public void cancel(Throwable cause) {
        requireNonNull(cause, "cause");
        requestCancellationScheduler.finishNow(cause);
    }

    @Nullable
    @Override
    public Throwable cancellationCause() {
        return requestCancellationScheduler.cause();
    }

    @Override
    public CompletableFuture<Throwable> whenRequestCancelling() {
        return requestCancellationScheduler.whenCancelling();
    }

    @Override
    public CompletableFuture<Throwable> whenRequestCancelled() {
        return requestCancellationScheduler.whenCancelled();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenRequestTimingOut() {
        return requestCancellationScheduler.whenTimingOut();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenRequestTimedOut() {
        return requestCancellationScheduler.whenTimedOut();
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
    public CompletableFuture<Void> initiateConnectionShutdown(long drainDurationMicros) {
        return initiateConnectionShutdown(InitiateConnectionShutdown.of(drainDurationMicros));
    }

    @Override
    public CompletableFuture<Void> initiateConnectionShutdown() {
        return initiateConnectionShutdown(InitiateConnectionShutdown.of());
    }

    private CompletableFuture<Void> initiateConnectionShutdown(InitiateConnectionShutdown event) {
        if (!ch.isActive()) {
            return UnmodifiableFuture.completedFuture(null);
        }
        final CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        ch.closeFuture().addListener(f -> {
            if (f.cause() == null) {
                completableFuture.complete(null);
            } else {
                completableFuture.completeExceptionally(f.cause());
            }
        });
        ch.pipeline().fireUserEventTriggered(event);
        return completableFuture;
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
    public String toString() {
        if (strVal != null) {
            return strVal;
        } else {
            return toStringSlow();
        }
    }

    private String toStringSlow() {
        // Prepare all properties required for building a String, so that we don't have a chance of
        // building one String with a thread-local StringBuilder while building another String with
        // the same StringBuilder. See TemporaryThreadLocals for more information.
        final String sreqId = id().shortText();
        final String chanId = ch.id().asShortText();
        final InetSocketAddress raddr = remoteAddress();
        final InetSocketAddress laddr = localAddress();
        final InetAddress caddr = clientAddress();
        final String proto = sessionProtocol().uriText();
        final String authority = config().virtualHost().defaultHostname();
        final String path = path();
        final String method = method().name();

        // Build the string representation.
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("[sreqId=").append(sreqId)
               .append(", chanId=").append(chanId);

            if (!Objects.equals(caddr, raddr.getAddress())) {
                buf.append(", caddr=");
                TextFormatter.appendInetAddress(buf, caddr);
            }

            buf.append(", raddr=");
            TextFormatter.appendSocketAddress(buf, raddr);
            buf.append(", laddr=");
            TextFormatter.appendSocketAddress(buf, laddr);
            buf.append("][")
               .append(proto).append("://").append(authority).append(path).append('#').append(method)
               .append(']');

            return strVal = buf.toString();
        }
    }
}
