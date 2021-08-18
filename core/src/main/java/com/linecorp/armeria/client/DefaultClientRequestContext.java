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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.SSLSession;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;

/**
 * Default {@link ClientRequestContext} implementation.
 */
@UnstableApi
public final class DefaultClientRequestContext
        extends NonWrappingRequestContext
        implements ClientRequestContext {

    private static final AtomicReferenceFieldUpdater<DefaultClientRequestContext, HttpHeaders>
            additionalRequestHeadersUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultClientRequestContext.class, HttpHeaders.class, "additionalRequestHeaders");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultClientRequestContext, CompletableFuture>
            whenInitializedUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultClientRequestContext.class, CompletableFuture.class, "whenInitialized");

    private static final short STR_CHANNEL_AVAILABILITY = 1;
    private static final short STR_PARENT_LOG_AVAILABILITY = 1 << 1;

    private boolean initialized;
    @Nullable
    private EventLoop eventLoop;
    @Nullable
    private EndpointGroup endpointGroup;
    @Nullable
    private Endpoint endpoint;
    @Nullable
    private ContextAwareEventLoop contextAwareEventLoop;
    @Nullable
    private final String fragment;
    @Nullable
    private final ServiceRequestContext root;

    private final boolean hasBaseUri;
    private final ClientOptions options;
    private final RequestLogBuilder log;
    private final CancellationScheduler responseCancellationScheduler;
    private long writeTimeoutMillis;
    private long maxResponseLength;

    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalRequestHeadersUpdater`
    private volatile HttpHeaders additionalRequestHeaders;

    @Nullable
    private String strVal;
    private short strValAvailabilities;

    // We use null checks which are faster than checking if a list is empty,
    // because it is more common to have no customizers than to have any.
    @Nullable
    private volatile List<Consumer<? super ClientRequestContext>> customizers;

    @Nullable
    private volatile CompletableFuture<Boolean> whenInitialized;

    /**
     * Creates a new instance. Note that {@link #init(EndpointGroup)} method must be invoked to finish
     * the construction of this context.
     *
     * @param eventLoop the {@link EventLoop} associated with this context
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param id the {@link RequestId} that represents the identifier of the current {@link Request}
     *           and {@link Response} pair.
     * @param req the {@link HttpRequest} associated with this context
     * @param rpcReq the {@link RpcRequest} associated with this context
     * @param requestStartTimeNanos {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch,
     *                               e.g. {@code System.currentTimeMillis() * 1000}.
     */
    DefaultClientRequestContext(
            EventLoop eventLoop, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
            ClientOptions options, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            RequestOptions requestOptions, CancellationScheduler responseCancellationScheduler,
            long requestStartTimeNanos, long requestStartTimeMicros) {
        this(eventLoop, meterRegistry, sessionProtocol,
             id, method, path, query, fragment, options, req, rpcReq, requestOptions, serviceRequestContext(),
             responseCancellationScheduler, requestStartTimeNanos, requestStartTimeMicros, false);
    }

    /**
     * Creates a new instance. Note that {@link #init(EndpointGroup)} method must be invoked to finish
     * the construction of this context.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param id the {@link RequestId} that contains the identifier of the current {@link Request}
     *           and {@link Response} pair.
     * @param req the {@link HttpRequest} associated with this context
     * @param rpcReq the {@link RpcRequest} associated with this context
     * @param requestStartTimeNanos {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch,
     *                               e.g. {@code System.currentTimeMillis() * 1000}.
     * @param hasBaseUri whether a {@link Client} which initiates this {@link ClientRequestContext} was
     *                   created with a base {@link URI}.
     */
    public DefaultClientRequestContext(
            MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
            ClientOptions options, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            RequestOptions requestOptions,
            long requestStartTimeNanos, long requestStartTimeMicros, boolean hasBaseUri) {
        this(null, meterRegistry, sessionProtocol,
             id, method, path, query, fragment, options, req, rpcReq, requestOptions,
             serviceRequestContext(), /* responseCancellationScheduler */ null,
             requestStartTimeNanos, requestStartTimeMicros, hasBaseUri);
    }

    private DefaultClientRequestContext(
            @Nullable EventLoop eventLoop, MeterRegistry meterRegistry,
            SessionProtocol sessionProtocol, RequestId id, HttpMethod method, String path,
            @Nullable String query, @Nullable String fragment, ClientOptions options,
            @Nullable HttpRequest req, @Nullable RpcRequest rpcReq, RequestOptions requestOptions,
            @Nullable ServiceRequestContext root, @Nullable CancellationScheduler responseCancellationScheduler,
            long requestStartTimeNanos, long requestStartTimeMicros, boolean hasBaseUri) {
        super(meterRegistry, sessionProtocol, id, method, path, query, req, rpcReq, root);

        this.eventLoop = eventLoop;
        this.hasBaseUri = hasBaseUri;
        this.options = requireNonNull(options, "options");
        this.fragment = fragment;
        this.root = root;

        log = RequestLog.builder(this);
        log.startRequest(requestStartTimeNanos, requestStartTimeMicros);

        if (responseCancellationScheduler == null) {
            long responseTimeoutMillis = requestOptions.responseTimeoutMillis();
            if (responseTimeoutMillis < 0) {
                responseTimeoutMillis = options().responseTimeoutMillis();
            }
            this.responseCancellationScheduler =
                    new CancellationScheduler(TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis));
        } else {
            this.responseCancellationScheduler = responseCancellationScheduler;
        }

        long writeTimeoutMillis = requestOptions.writeTimeoutMillis();
        if (writeTimeoutMillis < 0) {
            writeTimeoutMillis = options.writeTimeoutMillis();
        }
        this.writeTimeoutMillis = writeTimeoutMillis;

        long maxResponseLength = requestOptions.maxResponseLength();
        if (maxResponseLength < 0) {
            maxResponseLength = options.maxResponseLength();
        }
        this.maxResponseLength = maxResponseLength;
        for (Entry<AttributeKey<?>, Object> attr : requestOptions.attrs().entrySet()) {
            //noinspection unchecked
            setAttr((AttributeKey<Object>) attr.getKey(), attr.getValue());
        }

        additionalRequestHeaders = options.get(ClientOptions.HEADERS);
        customizers = copyThreadLocalCustomizers();
    }

    @Nullable
    private static ServiceRequestContext serviceRequestContext() {
        final RequestContext current = RequestContext.currentOrNull();
        return current != null ? current.root() : null;
    }

    /**
     * Initializes this context with the specified {@link EndpointGroup}.
     * This method must be invoked to finish the construction of this context.
     *
     * @return {@code true} if the initialization has succeeded.
     *         {@code false} if the initialization has failed and this context's {@link RequestLog} has been
     *         completed with the cause of the failure.
     */
    public CompletableFuture<Boolean> init(EndpointGroup endpointGroup) {
        assert endpoint == null : endpoint;
        assert !initialized;
        initialized = true;

        try {
            // Note: thread-local customizer must be run before:
            //       - EndpointSelector.select() so that the customizer can inject the attributes which may be
            //         required by the EndpointSelector.
            //       - mapEndpoint() to give an opportunity to override an Endpoint when using
            //         an additional authority.
            runThreadLocalContextCustomizers();

            endpointGroup = mapEndpoint(endpointGroup, hasBaseUri);
            if (endpointGroup instanceof Endpoint) {
                return initEndpoint((Endpoint) endpointGroup);
            } else {
                return initEndpointGroup(endpointGroup);
            }
        } catch (Throwable t) {
            acquireEventLoop(endpointGroup);
            failEarly(t);
            return initFuture(false, null);
        }
    }

    private EndpointGroup mapEndpoint(EndpointGroup endpointGroup, boolean hasBaseUri) {
        if (endpointGroup instanceof Endpoint) {
            Endpoint endpoint = (Endpoint) endpointGroup;
            if (!hasBaseUri) {
                // If a WebClient was created without a base URI, an authority header could be
                // the host of an Endpoint.
                final String authority = additionalRequestHeaders.get(HttpHeaderNames.AUTHORITY);
                if (authority != null) {
                    try {
                        endpoint = Endpoint.parse(authority);
                    } catch (Exception ignored) {
                        // Ignore an invalid authority and use the original endpoint.
                    }
                }
            }
            return requireNonNull(options().endpointRemapper().apply(endpoint),
                                  "endpointRemapper returned null.");
        } else {
            return endpointGroup;
        }
    }

    private CompletableFuture<Boolean> initEndpoint(Endpoint endpoint) {
        endpointGroup = null;
        updateEndpoint(endpoint);
        acquireEventLoop(endpoint);
        return initFuture(true, null);
    }

    private CompletableFuture<Boolean> initEndpointGroup(EndpointGroup endpointGroup) {
        this.endpointGroup = endpointGroup;
        final Endpoint endpoint = endpointGroup.selectNow(this);
        if (endpoint != null) {
            updateEndpoint(endpoint);
            acquireEventLoop(endpointGroup);
            return initFuture(true, null);
        }

        // Use an arbitrary event loop for asynchronous Endpoint selection.
        final EventLoop temporaryEventLoop = options().factory().eventLoopSupplier().get();
        return endpointGroup.select(this, temporaryEventLoop, connectTimeoutMillis()).handle((e, cause) -> {
            updateEndpoint(e);
            acquireEventLoop(endpointGroup);

            final boolean success;
            if (cause != null) {
                failEarly(cause);
                success = false;
            } else {
                success = true;
            }

            final EventLoop acquiredEventLoop = eventLoop();
            if (acquiredEventLoop == temporaryEventLoop) {
                // We were lucky. No need to hand over to other EventLoop.
                return initFuture(success, null);
            } else {
                // We need to hand over to the acquired EventLoop.
                return initFuture(success, acquiredEventLoop);
            }
        }).thenCompose(Function.identity());
    }

    private static CompletableFuture<Boolean> initFuture(boolean success,
                                                         @Nullable EventLoop acquiredEventLoop) {
        if (acquiredEventLoop == null) {
            return UnmodifiableFuture.completedFuture(success);
        } else {
            return CompletableFuture.supplyAsync(() -> success, acquiredEventLoop);
        }
    }

    /**
     * Returns a {@link CompletableFuture} that will be completed
     * if this {@link ClientRequestContext} is initialized with an {@link EndpointGroup}.
     *
     * @see #init(EndpointGroup)
     */
    public CompletableFuture<Boolean> whenInitialized() {
        CompletableFuture<Boolean> whenInitialized = this.whenInitialized;
        if (whenInitialized != null) {
            return whenInitialized;
        } else {
            whenInitialized = new CompletableFuture<>();
            if (whenInitializedUpdater.compareAndSet(this, null, whenInitialized)) {
                return whenInitialized;
            } else {
                return this.whenInitialized;
            }
        }
    }

    /**
     * Completes the {@link #whenInitialized()} with the specified value.
     */
    public void finishInitialization(boolean success) {
        final CompletableFuture<Boolean> whenInitialized = this.whenInitialized;
        if (whenInitialized != null) {
            whenInitialized.complete(success);
        } else {
            if (!whenInitializedUpdater.compareAndSet(this, null,
                                                      UnmodifiableFuture.completedFuture(success))) {
                this.whenInitialized.complete(success);
            }
        }
    }

    private void updateEndpoint(@Nullable Endpoint endpoint) {
        this.endpoint = endpoint;
        autoFillSchemeAndAuthority();
    }

    private void acquireEventLoop(EndpointGroup endpointGroup) {
        if (eventLoop == null) {
            final ReleasableHolder<EventLoop> releasableEventLoop =
                    options().factory().acquireEventLoop(sessionProtocol(), endpointGroup, endpoint);
            eventLoop = releasableEventLoop.get();
            log.whenComplete().thenAccept(unused -> releasableEventLoop.release());
        }
    }

    private void runThreadLocalContextCustomizers() {
        final List<Consumer<? super ClientRequestContext>> customizers = this.customizers;
        if (customizers != null) {
            this.customizers = null;
            for (Consumer<? super ClientRequestContext> c : customizers) {
                c.accept(this);
            }
        }
    }

    private long connectTimeoutMillis() {
        final Integer boxedConnectTimeoutMillis =
                (Integer) options.factory().options().channelOptions().get(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS);
        return boxedConnectTimeoutMillis != null ? boxedConnectTimeoutMillis.longValue()
                                                 : Flags.defaultConnectTimeoutMillis();
    }

    private void failEarly(Throwable cause) {
        final UnprocessedRequestException wrapped = UnprocessedRequestException.of(cause);
        final HttpRequest req = request();
        if (req != null) {
            autoFillSchemeAndAuthority();
            req.abort(wrapped);
        }

        final RequestLogBuilder logBuilder = logBuilder();
        logBuilder.endRequest(wrapped);
        logBuilder.endResponse(wrapped);
    }

    private void autoFillSchemeAndAuthority() {
        final HttpRequest req = request();
        if (req == null) {
            return;
        }

        final RequestHeaders headers = req.headers();
        final String authority = endpoint != null ? endpoint.authority() : "UNKNOWN";
        if (headers.scheme() == null || !authority.equals(headers.authority())) {
            final RequestHeadersBuilder headersBuilder =
                    headers.toBuilder();
            if (headers.scheme() == null) {
                headersBuilder.scheme(sessionProtocol());
            }
            if (headersBuilder.get(HttpHeaderNames.HOST) != null) {
                headersBuilder.set(HttpHeaderNames.HOST, authority);
            } else {
                headersBuilder.authority(authority);
            }
            unsafeUpdateRequest(req.withHeaders(headersBuilder));
        }
    }

    /**
     * Creates a derived context.
     */
    private DefaultClientRequestContext(DefaultClientRequestContext ctx,
                                        RequestId id,
                                        @Nullable HttpRequest req,
                                        @Nullable RpcRequest rpcReq,
                                        @Nullable Endpoint endpoint) {
        super(ctx.meterRegistry(), ctx.sessionProtocol(), id, ctx.method(), ctx.path(), ctx.query(),
              req, rpcReq, ctx.root());

        // The new requests cannot be null if it was previously non-null.
        if (ctx.request() != null) {
            requireNonNull(req, "req");
        }
        // The rpcReq can be null when ctx.rpcRequest() is not null because there's a chance that
        // the rpcRequest is set between the time we call ctx.rpcRequest() to create this context and now.
        // So we don't check the nullness of rpcRequest unlike request.
        // See https://github.com/line/armeria/pull/3251 and https://github.com/line/armeria/issues/3248.

        eventLoop = ctx.eventLoop().withoutContext();
        hasBaseUri = ctx.hasBaseUri;
        options = ctx.options();
        endpointGroup = ctx.endpointGroup();
        updateEndpoint(endpoint);
        fragment = ctx.fragment();
        root = ctx.root();

        log = RequestLog.builder(this);
        log.startRequest();
        responseCancellationScheduler =
                new CancellationScheduler(TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis()));
        writeTimeoutMillis = ctx.writeTimeoutMillis();
        maxResponseLength = ctx.maxResponseLength();
        additionalRequestHeaders = ctx.additionalRequestHeaders();

        for (final Iterator<Entry<AttributeKey<?>, Object>> i = ctx.ownAttrs(); i.hasNext();) {
            addAttr(i.next());
        }
    }

    @Nullable
    private List<Consumer<? super ClientRequestContext>> copyThreadLocalCustomizers() {
        final ClientThreadLocalState state = ClientThreadLocalState.get();
        if (state == null) {
            return null;
        }

        state.addCapturedContext(this);
        return state.copyCustomizers();
    }

    @SuppressWarnings("unchecked")
    private <T> void addAttr(Entry<AttributeKey<?>, Object> attribute) {
        setAttr((AttributeKey<T>) attribute.getKey(), (T) attribute.getValue());
    }

    @Nullable
    @Override
    public ServiceRequestContext root() {
        return root;
    }

    @Override
    public ClientRequestContext newDerivedContext(RequestId id,
                                                  @Nullable HttpRequest req,
                                                  @Nullable RpcRequest rpcReq,
                                                  @Nullable Endpoint endpoint) {
        return new DefaultClientRequestContext(this, id, req, rpcReq, endpoint);
    }

    @Override
    protected void validateHeaders(RequestHeaders headers) {
        // Do not validate if the context is not fully initialized yet,
        // because init() will trigger this method again via updateEndpoint().
        if (!initialized) {
            return;
        }

        super.validateHeaders(headers);
    }

    @Override
    @Nullable
    protected Channel channel() {
        if (log.isAvailable(RequestLogProperty.SESSION)) {
            return log.partial().channel();
        } else {
            return null;
        }
    }

    @Override
    public ContextAwareEventLoop eventLoop() {
        checkState(eventLoop != null, "Should call init(endpoint) before invoking this method.");
        if (contextAwareEventLoop != null) {
            return contextAwareEventLoop;
        }
        return contextAwareEventLoop = ContextAwareEventLoop.of(this, eventLoop);
    }

    @Override
    public ByteBufAllocator alloc() {
        final Channel channel = channel();
        return channel != null ? channel.alloc() : PooledByteBufAllocator.DEFAULT;
    }

    @Nullable
    @Override
    public SSLSession sslSession() {
        if (log.isAvailable(RequestLogProperty.SESSION)) {
            return log.partial().sslSession();
        } else {
            return null;
        }
    }

    @Override
    public ClientOptions options() {
        return options;
    }

    @Override
    public EndpointGroup endpointGroup() {
        return endpointGroup;
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
        checkArgument(writeTimeoutMillis >= 0,
                      "writeTimeoutMillis: %s (expected: >= 0)", writeTimeoutMillis);
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    @Override
    public void setWriteTimeout(Duration writeTimeout) {
        setWriteTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    @Override
    public long responseTimeoutMillis() {
        return TimeUnit.NANOSECONDS.toMillis(responseCancellationScheduler.timeoutNanos());
    }

    @Override
    public void clearResponseTimeout() {
        responseCancellationScheduler.clearTimeout();
    }

    @Override
    public void setResponseTimeoutMillis(TimeoutMode mode, long responseTimeoutMillis) {
        responseCancellationScheduler.setTimeoutNanos(requireNonNull(mode, "mode"),
                                                      TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis));
    }

    @Override
    public void setResponseTimeout(TimeoutMode mode, Duration responseTimeout) {
        responseCancellationScheduler.setTimeoutNanos(
                requireNonNull(mode, "mode"),
                requireNonNull(responseTimeout, "responseTimeout").toNanos());
    }

    @Override
    public long maxResponseLength() {
        return maxResponseLength;
    }

    @Override
    public void setMaxResponseLength(long maxResponseLength) {
        checkArgument(maxResponseLength >= 0, "maxResponseLength: %s (expected: >= 0)", maxResponseLength);
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
        mutateAdditionalRequestHeaders(builder -> builder.setObject(name, value));
    }

    @Override
    public void addAdditionalRequestHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        mutateAdditionalRequestHeaders(builder -> builder.addObject(name, value));
    }

    @Override
    public void mutateAdditionalRequestHeaders(Consumer<HttpHeadersBuilder> mutator) {
        requireNonNull(mutator, "mutator");
        for (;;) {
            final HttpHeaders oldValue = additionalRequestHeaders;
            final HttpHeadersBuilder builder = oldValue.toBuilder();
            mutator.accept(builder);
            final HttpHeaders newValue = builder.build();
            if (additionalRequestHeadersUpdater.compareAndSet(this, oldValue, newValue)) {
                return;
            }
        }
    }

    @Override
    public RequestLogAccess log() {
        return log;
    }

    @Override
    public RequestLogBuilder logBuilder() {
        return log;
    }

    CancellationScheduler responseCancellationScheduler() {
        return responseCancellationScheduler;
    }

    @Override
    public void cancel(Throwable cause) {
        requireNonNull(cause, "cause");
        responseCancellationScheduler.finishNow(cause);
    }

    @Nullable
    @Override
    public Throwable cancellationCause() {
        return responseCancellationScheduler.cause();
    }

    @Override
    public CompletableFuture<Throwable> whenResponseCancelling() {
        return responseCancellationScheduler.whenCancelling();
    }

    @Override
    public CompletableFuture<Throwable> whenResponseCancelled() {
        return responseCancellationScheduler.whenCancelled();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenResponseTimingOut() {
        return responseCancellationScheduler.whenTimingOut();
    }

    @Deprecated
    @Override
    public CompletableFuture<Void> whenResponseTimedOut() {
        return responseCancellationScheduler.whenTimedOut();
    }

    @Override
    public String toString() {
        final Channel ch = channel();
        final RequestLogAccess parent = log().parent();
        final short newAvailability =
                (short) ((ch != null ? STR_CHANNEL_AVAILABILITY : 0) |
                         (parent != null ? STR_PARENT_LOG_AVAILABILITY : 0));
        if (strVal != null && strValAvailabilities == newAvailability) {
            return strVal;
        }

        strValAvailabilities = newAvailability;
        return strVal = toStringSlow(ch, parent);
    }

    private String toStringSlow(@Nullable Channel ch, @Nullable RequestLogAccess parent) {
        // Prepare all properties required for building a String, so that we don't have a chance of
        // building one String with a thread-local StringBuilder while building another String with
        // the same StringBuilder. See TemporaryThreadLocals for more information.
        final String creqId = id().shortText();
        final String preqId = parent != null ? parent.context().id().shortText() : null;
        final String sreqId = root() != null ? root().id().shortText() : null;
        final String chanId = ch != null ? ch.id().asShortText() : null;
        final String proto = sessionProtocol().uriText();
        final String authority = endpoint != null ? endpoint.authority() : "UNKNOWN";
        final String path = path();
        final String method = method().name();

        // Build the string representation.
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("[creqId=").append(creqId);
            if (parent != null) {
                buf.append(", preqId=").append(preqId);
            }
            if (sreqId != null) {
                buf.append(", sreqId=").append(sreqId);
            }
            if (ch != null) {
                buf.append(", chanId=").append(chanId)
                   .append(", laddr=");
                TextFormatter.appendSocketAddress(buf, ch.localAddress());
                buf.append(", raddr=");
                TextFormatter.appendSocketAddress(buf, ch.remoteAddress());
            }
            buf.append("][")
               .append(proto).append("://").append(authority).append(path).append('#').append(method)
               .append(']');

            return buf.toString();
        }
    }
}
