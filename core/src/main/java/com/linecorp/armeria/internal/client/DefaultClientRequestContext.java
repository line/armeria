/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.internal.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.HttpHeadersUtil.getScheme;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.SSLSession;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AttributesGetters;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.NonWrappingRequestContext;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.internal.common.SchemeAndAuthority;
import com.linecorp.armeria.internal.common.stream.FixedStreamMessage;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

/**
 * Default {@link ClientRequestContext} implementation.
 */
public final class DefaultClientRequestContext
        extends NonWrappingRequestContext
        implements ClientRequestContextExtension {

    private static final AtomicReferenceFieldUpdater<DefaultClientRequestContext, HttpHeaders>
            additionalRequestHeadersUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultClientRequestContext.class, HttpHeaders.class, "additionalRequestHeaders");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultClientRequestContext, CompletableFuture>
            whenInitializedUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultClientRequestContext.class, CompletableFuture.class, "whenInitialized");

    private static SessionProtocol desiredSessionProtocol(SessionProtocol protocol, ClientOptions options) {
        if (!options.factory().options().preferHttp1()) {
            return protocol;
        }
        switch (protocol) {
            case HTTP:
                return SessionProtocol.H1C;
            case HTTPS:
                return SessionProtocol.H1;
            default:
                return protocol;
        }
    }

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
    private Channel channel;
    @Nullable
    private InetSocketAddress remoteAddress;
    @Nullable
    private InetSocketAddress localAddress;
    @Nullable
    private final ServiceRequestContext root;

    private final ClientOptions options;
    private final RequestLogBuilder log;
    private final CancellationScheduler responseCancellationScheduler;
    private long writeTimeoutMillis;
    private long maxResponseLength;

    private final HttpHeaders defaultRequestHeaders;
    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalRequestHeadersUpdater`
    private volatile HttpHeaders additionalRequestHeaders;

    private static final HttpHeaders defaultInternalRequestHeaders =
            HttpHeaders.of(HttpHeaderNames.USER_AGENT, UserAgentUtil.USER_AGENT.toString());
    private HttpHeaders internalRequestHeaders = defaultInternalRequestHeaders;

    @Nullable
    private String strVal;
    private short strValAvailabilities;

    // We use null checks which are faster than checking if a list is empty,
    // because it is more common to have no customizers than to have any.
    @Nullable
    private volatile Consumer<ClientRequestContext> customizer;

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
    public DefaultClientRequestContext(
            @Nullable EventLoop eventLoop, MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, HttpMethod method, RequestTarget reqTarget,
            ClientOptions options, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            RequestOptions requestOptions, CancellationScheduler responseCancellationScheduler,
            long requestStartTimeNanos, long requestStartTimeMicros) {
        this(eventLoop, meterRegistry, sessionProtocol,
             id, method, reqTarget, options, req, rpcReq, requestOptions, serviceRequestContext(),
             requireNonNull(responseCancellationScheduler, "responseCancellationScheduler"),
             requestStartTimeNanos, requestStartTimeMicros);
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
     */
    public DefaultClientRequestContext(
            MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, HttpMethod method, RequestTarget reqTarget,
            ClientOptions options, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            RequestOptions requestOptions,
            long requestStartTimeNanos, long requestStartTimeMicros) {
        this(null, meterRegistry, sessionProtocol,
             id, method, reqTarget, options, req, rpcReq, requestOptions,
             serviceRequestContext(), /* responseCancellationScheduler */ null,
             requestStartTimeNanos, requestStartTimeMicros);
    }

    private DefaultClientRequestContext(
            @Nullable EventLoop eventLoop, MeterRegistry meterRegistry,
            SessionProtocol sessionProtocol, RequestId id, HttpMethod method,
            RequestTarget reqTarget, ClientOptions options,
            @Nullable HttpRequest req, @Nullable RpcRequest rpcReq, RequestOptions requestOptions,
            @Nullable ServiceRequestContext root, @Nullable CancellationScheduler responseCancellationScheduler,
            long requestStartTimeNanos, long requestStartTimeMicros) {
        super(meterRegistry, desiredSessionProtocol(sessionProtocol, options), id, method, reqTarget,
              guessExchangeType(requestOptions, req),
              requestAutoAbortDelayMillis(options, requestOptions), req, rpcReq,
              getAttributes(root), options.contextHook());

        this.eventLoop = eventLoop;
        this.options = requireNonNull(options, "options");
        this.root = root;

        log = RequestLog.builder(this);
        log.startRequest(requestStartTimeNanos, requestStartTimeMicros);

        if (responseCancellationScheduler == null) {
            long responseTimeoutMillis = requestOptions.responseTimeoutMillis();
            if (responseTimeoutMillis < 0) {
                responseTimeoutMillis = options().responseTimeoutMillis();
            }
            this.responseCancellationScheduler =
                    CancellationScheduler.ofClient(TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis));
            // the cancellationScheduler is not initialized here since the eventLoop is guaranteed to be null
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

        defaultRequestHeaders = options.get(ClientOptions.HEADERS);
        additionalRequestHeaders = HttpHeaders.of();

        final Consumer<ClientRequestContext> customizer = options.contextCustomizer();
        final Consumer<ClientRequestContext> threadLocalCustomizer = copyThreadLocalCustomizer();
        if (customizer == ClientOptions.CONTEXT_CUSTOMIZER.defaultValue()) {
            this.customizer = threadLocalCustomizer;
        } else if (threadLocalCustomizer == null) {
            this.customizer = customizer;
        } else {
            this.customizer = customizer.andThen(threadLocalCustomizer);
        }
    }

    private static ExchangeType guessExchangeType(RequestOptions requestOptions, @Nullable HttpRequest req) {
        final ExchangeType exchangeType = requestOptions.exchangeType();
        if (exchangeType != null) {
            return exchangeType;
        }
        if (req instanceof FixedStreamMessage) {
            return ExchangeType.RESPONSE_STREAMING;
        }
        return ExchangeType.BIDI_STREAMING;
    }

    private static long requestAutoAbortDelayMillis(ClientOptions options, RequestOptions requestOptions) {
        final Long requestAutoAbortDelayMillis = requestOptions.requestAutoAbortDelayMillis();
        if (requestAutoAbortDelayMillis != null) {
            return requestAutoAbortDelayMillis;
        }
        return options.requestAutoAbortDelayMillis();
    }

    @Nullable
    private static AttributesGetters getAttributes(@Nullable ServiceRequestContext ctx) {
        if (ctx == null) {
            return null;
        }
        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
        if (ctxExtension == null) {
            return null;
        }
        return ctxExtension.attributes();
    }

    @Nullable
    private static ServiceRequestContext serviceRequestContext() {
        final RequestContext current = RequestContext.currentOrNull();
        return current != null ? current.root() : null;
    }

    @Override
    public CompletableFuture<Boolean> init(EndpointGroup endpointGroup) {
        assert endpoint == null : endpoint;
        assert !initialized;
        initialized = true;

        try {
            // Note: context customizer must be run before:
            //       - EndpointSelector.select() so that the customizer can inject the attributes which may be
            //         required by the EndpointSelector.
            //       - mapEndpoint() to give an opportunity to override an Endpoint when using
            //         an additional authority.
            runContextCustomizer();

            endpointGroup = mapEndpoint(endpointGroup);
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

    private EndpointGroup mapEndpoint(EndpointGroup endpointGroup) {
        if (endpointGroup instanceof Endpoint) {
            return requireNonNull(options().endpointRemapper().apply((Endpoint) endpointGroup),
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
        return endpointGroup.select(this, temporaryEventLoop).handle((e, cause) -> {
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

    @Override
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

    @Override
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
        autoFillSchemeAuthorityAndOrigin();
    }

    private void acquireEventLoop(EndpointGroup endpointGroup) {
        if (eventLoop == null) {
            final ReleasableHolder<EventLoop> releasableEventLoop =
                    options().factory().acquireEventLoop(sessionProtocol(), endpointGroup, endpoint);
            eventLoop = releasableEventLoop.get();
            log.whenComplete().thenAccept(unused -> releasableEventLoop.release());
            responseCancellationScheduler.init(eventLoop());
        }
    }

    private void runContextCustomizer() {
        final Consumer<ClientRequestContext> customizer = this.customizer;
        if (customizer != null) {
            this.customizer = null;
            customizer.accept(this);
        }
    }

    private void failEarly(Throwable cause) {
        final UnprocessedRequestException wrapped = UnprocessedRequestException.of(cause);
        final HttpRequest req = request();
        if (req != null) {
            autoFillSchemeAuthorityAndOrigin();
            req.abort(wrapped);
        }

        final RequestLogBuilder logBuilder = logBuilder();
        logBuilder.endRequest(wrapped);
        logBuilder.endResponse(wrapped);
    }

    // TODO(ikhoon): Consider moving the logic for filling authority to `HttpClientDelegate.exceute()`.
    private void autoFillSchemeAuthorityAndOrigin() {
        final String authority = authority();
        if (authority != null && endpoint != null && endpoint.isIpAddrOnly()) {
            // The connection will be established with the IP address but `host` set to the `Endpoint`
            // could be used for SNI. It would make users send HTTPS requests with CSLB or configure a reverse
            // proxy based on an authority.
            final String host = SchemeAndAuthority.of(null, authority).host();
            if (!NetUtil.isValidIpV4Address(host) && !NetUtil.isValidIpV6Address(host)) {
                endpoint = endpoint.withHost(host);
            }
        }

        final HttpHeadersBuilder headersBuilder = internalRequestHeaders.toBuilder();
        headersBuilder.set(HttpHeaderNames.SCHEME, getScheme(sessionProtocol()));
        if (endpoint != null) {
            final String endpointAuthority = endpoint.authority();
            headersBuilder.set(HttpHeaderNames.AUTHORITY, endpointAuthority);
            final String origin = origin();
            if (origin != null) {
                headersBuilder.set(HttpHeaderNames.ORIGIN, origin);
            } else if (options().autoFillOriginHeader()) {
                final String uriText = sessionProtocol().isTls() ? SessionProtocol.HTTPS.uriText()
                                                                 : SessionProtocol.HTTP.uriText();
                headersBuilder.set(HttpHeaderNames.ORIGIN, uriText + "://" + endpointAuthority);
            }
        }
        internalRequestHeaders = headersBuilder.build();
    }

    /**
     * Creates a derived context.
     */
    private DefaultClientRequestContext(DefaultClientRequestContext ctx,
                                        RequestId id,
                                        @Nullable HttpRequest req,
                                        @Nullable RpcRequest rpcReq,
                                        @Nullable Endpoint endpoint, @Nullable EndpointGroup endpointGroup,
                                        SessionProtocol sessionProtocol, HttpMethod method,
                                        RequestTarget reqTarget) {
        super(ctx.meterRegistry(), sessionProtocol, id, method, reqTarget, ctx.exchangeType(),
              ctx.requestAutoAbortDelayMillis(), req, rpcReq, getAttributes(ctx.root()), ctx.hook());

        // The new requests cannot be null if it was previously non-null.
        if (ctx.request() != null) {
            requireNonNull(req, "req");
        }
        // The rpcReq can be null when ctx.rpcRequest() is not null because there's a chance that
        // the rpcRequest is set between the time we call ctx.rpcRequest() to create this context and now.
        // So we don't check the nullness of rpcRequest unlike request.
        // See https://github.com/line/armeria/pull/3251 and https://github.com/line/armeria/issues/3248.

        options = ctx.options();
        root = ctx.root();

        log = RequestLog.builder(this);
        log.startRequest();
        responseCancellationScheduler =
                CancellationScheduler.ofClient(TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis()));
        writeTimeoutMillis = ctx.writeTimeoutMillis();
        maxResponseLength = ctx.maxResponseLength();

        defaultRequestHeaders = ctx.defaultRequestHeaders();
        additionalRequestHeaders = ctx.additionalRequestHeaders();

        for (final Iterator<Entry<AttributeKey<?>, Object>> i = ctx.ownAttrs(); i.hasNext();) {
            addAttr(i.next());
        }

        this.endpointGroup = endpointGroup;
        updateEndpoint(endpoint);
        // We don't need to acquire an EventLoop for the initial attempt because it's already acquired by
        // the root context.
        if (endpoint == null || ctx.endpoint() == endpoint && ctx.log.children().isEmpty()) {
            eventLoop = ctx.eventLoop().withoutContext();
            responseCancellationScheduler.init(eventLoop());
        } else {
            acquireEventLoop(endpoint);
        }
    }

    @Nullable
    private Consumer<ClientRequestContext> copyThreadLocalCustomizer() {
        final ClientThreadLocalState state = ClientThreadLocalState.get();
        if (state == null) {
            return null;
        }

        state.addCapturedContext(this);
        final List<Consumer<? super ClientRequestContext>> customizers = state.copyCustomizers();
        if (customizers == null) {
            return null;
        } else {
            return ctx -> {
                for (Consumer<? super ClientRequestContext> c : customizers) {
                    c.accept(this);
                }
            };
        }
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
        if (req != null) {
            final RequestHeaders newHeaders = req.headers();
            final String oldPath = requestTarget().pathAndQuery();
            final String newPath = newHeaders.path();
            if (!oldPath.equals(newPath)) {
                // path is changed.
                final RequestTarget reqTarget = RequestTarget.forClient(newPath);
                checkArgument(reqTarget != null, "invalid path: %s", newPath);

                if (reqTarget.form() != RequestTargetForm.ABSOLUTE) {
                    // Not an absolute URI.
                    return new DefaultClientRequestContext(this, id, req, rpcReq, endpoint, null,
                                                           sessionProtocol(), newHeaders.method(), reqTarget);
                }

                // Recalculate protocol and endpoint from the absolute URI.
                final String scheme = reqTarget.scheme();
                final String authority = reqTarget.authority();
                assert scheme != null;
                assert authority != null;

                final SessionProtocol protocol = Scheme.parse(scheme).sessionProtocol();
                final Endpoint newEndpoint = Endpoint.parse(authority);
                final HttpRequest newReq = req.withHeaders(req.headers()
                                                              .toBuilder()
                                                              .path(reqTarget.pathAndQuery()));
                return new DefaultClientRequestContext(this, id, newReq, rpcReq, newEndpoint, null,
                                                       protocol, newHeaders.method(), reqTarget);
            }
        }
        return new DefaultClientRequestContext(this, id, req, rpcReq, endpoint, endpointGroup(),
                                               sessionProtocol(), method(), requestTarget());
    }

    @Override
    protected RequestTarget validateHeaders(RequestHeaders headers) {
        // no need to validate since internal headers will contain
        // the default host and session protocol headers set by endpoints.
        return RequestTarget.forClient(headers.path());
    }

    @Override
    @Nullable
    protected Channel channel() {
        final Channel channel = this.channel;
        if (channel != null) {
            return channel;
        }

        if (!log.isAvailable(RequestLogProperty.SESSION)) {
            return null;
        }

        final Channel newChannel = log.partial().channel();
        this.channel = newChannel;
        return newChannel;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        final InetSocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress != null) {
            return remoteAddress;
        }

        final InetSocketAddress newRemoteAddress = ChannelUtil.remoteAddress(channel());
        this.remoteAddress = newRemoteAddress;
        return newRemoteAddress;
    }

    @Override
    public InetSocketAddress localAddress() {
        final InetSocketAddress localAddress = this.localAddress;
        if (localAddress != null) {
            return localAddress;
        }

        final InetSocketAddress newLocalAddress = ChannelUtil.localAddress(channel());
        this.localAddress = newLocalAddress;
        return newLocalAddress;
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
        final RequestLog requestLog = log.getIfAvailable(RequestLogProperty.SESSION);
        return requestLog != null ? requestLog.sslSession() : null;
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
        return requestTarget().fragment();
    }

    @Override
    public String authority() {
        final HttpHeaders additionalRequestHeaders = this.additionalRequestHeaders;
        String authority = additionalRequestHeaders.get(HttpHeaderNames.AUTHORITY);
        if (authority == null) {
            authority = additionalRequestHeaders.get(HttpHeaderNames.HOST);
        }
        final HttpRequest request = request();
        if (authority == null && request != null) {
            authority = request.authority();
        }
        if (authority == null) {
            authority = defaultRequestHeaders.get(HttpHeaderNames.AUTHORITY);
        }
        if (authority == null) {
            authority = defaultRequestHeaders.get(HttpHeaderNames.HOST);
        }
        if (authority == null) {
            authority = internalRequestHeaders.get(HttpHeaderNames.AUTHORITY);
        }
        if (authority == null) {
            authority = internalRequestHeaders.get(HttpHeaderNames.HOST);
        }
        return authority;
    }

    @Nullable
    private String origin() {
        final HttpHeaders additionalRequestHeaders = this.additionalRequestHeaders;
        String origin = additionalRequestHeaders.get(HttpHeaderNames.ORIGIN);
        final HttpRequest request = request();
        if (origin == null && request != null) {
            origin = request.headers().get(HttpHeaderNames.ORIGIN);
        }
        if (origin == null) {
            origin = defaultRequestHeaders.get(HttpHeaderNames.ORIGIN);
        }
        if (origin == null) {
            origin = internalRequestHeaders.get(HttpHeaderNames.ORIGIN);
        }
        return origin;
    }

    @Override
    public String host() {
        final String authority = authority();
        if (authority == null) {
            return null;
        }
        return SchemeAndAuthority.of(null, authority).host();
    }

    @Override
    public URI uri() {
        final String scheme = getScheme(sessionProtocol());
        final String authority = authority();
        final String path = path();
        final String query = query();
        final String fragment = fragment();
        try (TemporaryThreadLocals tmp = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tmp.stringBuilder();
            buf.append(scheme);
            if (authority != null) {
                buf.append("://").append(authority);
            } else {
                buf.append(':');
            }
            buf.append(path);
            if (query != null) {
                buf.append('?').append(query);
            }
            if (fragment != null) {
                buf.append('#').append(fragment);
            }
            return new URI(buf.toString());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("not a valid URI", e);
        }
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
    public HttpHeaders defaultRequestHeaders() {
        return defaultRequestHeaders;
    }

    @Override
    public HttpHeaders additionalRequestHeaders() {
        return additionalRequestHeaders;
    }

    @Override
    public HttpHeaders internalRequestHeaders() {
        return internalRequestHeaders;
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

    @Override
    public CancellationScheduler responseCancellationScheduler() {
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
        return strVal = toStringSlow(parent);
    }

    private String toStringSlow(@Nullable RequestLogAccess parent) {
        // Prepare all properties required for building a String, so that we don't have a chance of
        // building one String with a thread-local StringBuilder while building another String with
        // the same StringBuilder. See TemporaryThreadLocals for more information.
        final Channel ch = channel();
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
                final InetSocketAddress laddr = localAddress();
                final InetSocketAddress raddr = remoteAddress();

                buf.append(", chanId=").append(chanId);
                buf.append(", laddr=");
                TextFormatter.appendSocketAddress(buf, laddr);
                if (!Objects.equals(laddr, raddr)) {
                    buf.append(", raddr=");
                    TextFormatter.appendSocketAddress(buf, raddr);
                }
            }
            buf.append("][")
               .append(proto).append("://").append(authority).append(path).append('#').append(method)
               .append(']');

            return buf.toString();
        }
    }

    @Override
    public CompletableFuture<Void> initiateConnectionShutdown() {
        final CompletableFuture<Void> completableFuture = new CompletableFuture<>();

        setAdditionalRequestHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        log().whenRequestComplete().thenAccept(log -> {
            final Channel ch = log.channel();
            if (ch == null) {
                final Throwable ex = log.requestCause();
                if (ex == null) {
                    completableFuture.completeExceptionally(new IllegalStateException(
                            "A request has failed before a connection is established."));
                } else {
                    completableFuture.completeExceptionally(ex);
                }
            } else {
                ch.closeFuture().addListener(f -> {
                    if (f.cause() == null) {
                        completableFuture.complete(null);
                    } else {
                        completableFuture.completeExceptionally(f.cause());
                    }
                });
                // To deactivate the channel when initiateShutdown is called after the RequestHeaders is sent.
                // The next request will trigger shutdown.
                HttpSession.get(ch).markUnacquirable();
            }
        });
        return completableFuture;
    }
}
