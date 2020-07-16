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

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;
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
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.TimeoutController;
import com.linecorp.armeria.internal.common.TimeoutScheduler;
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
    private static final short STR_CHANNEL_AVAILABILITY = 1;
    private static final short STR_PARENT_LOG_AVAILABILITY = 1 << 1;

    private boolean initialized;
    @Nullable
    private EventLoop eventLoop;
    private final ClientOptions options;
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

    private final RequestLogBuilder log;
    private final TimeoutScheduler timeoutScheduler;

    private long writeTimeoutMillis;
    @Nullable
    private Runnable responseTimeoutHandler;
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
            long requestStartTimeNanos, long requestStartTimeMicros) {
        this(eventLoop, meterRegistry, sessionProtocol,
             id, method, path, query, fragment, options, req, rpcReq, serviceRequestContext(),
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
            RequestId id, HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
            ClientOptions options, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            long requestStartTimeNanos, long requestStartTimeMicros) {
        this(null, meterRegistry, sessionProtocol,
             id, method, path, query, fragment, options, req, rpcReq, serviceRequestContext(),
             requestStartTimeNanos, requestStartTimeMicros);
    }

    private DefaultClientRequestContext(
            @Nullable EventLoop eventLoop, MeterRegistry meterRegistry,
            SessionProtocol sessionProtocol, RequestId id, HttpMethod method, String path,
            @Nullable String query, @Nullable String fragment, ClientOptions options,
            @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            @Nullable ServiceRequestContext root,
            long requestStartTimeNanos, long requestStartTimeMicros) {
        super(meterRegistry, sessionProtocol, id, method, path, query, req, rpcReq, root);

        this.eventLoop = eventLoop;
        this.options = requireNonNull(options, "options");
        this.fragment = fragment;
        this.root = root;

        log = RequestLog.builder(this);
        log.startRequest(requestStartTimeNanos, requestStartTimeMicros);
        timeoutScheduler = new TimeoutScheduler(TimeUnit.MILLISECONDS.toNanos(options.responseTimeoutMillis()));

        writeTimeoutMillis = options.writeTimeoutMillis();
        maxResponseLength = options.maxResponseLength();
        additionalRequestHeaders = options.get(ClientOption.HTTP_HEADERS);
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
            if (endpointGroup instanceof Endpoint) {
                return initEndpoint((Endpoint) endpointGroup);
            } else {
                return initEndpointGroup(endpointGroup);
            }
        } catch (Throwable t) {
            acquireEventLoop(endpointGroup);
            failEarly(t);
            return UnmodifiableFuture.completedFuture(false);
        }
    }

    private UnmodifiableFuture<Boolean> initEndpoint(Endpoint endpoint) {
        endpointGroup = null;
        updateEndpoint(endpoint);
        runThreadLocalContextCustomizers();
        acquireEventLoop(endpoint);
        return UnmodifiableFuture.completedFuture(true);
    }

    private CompletableFuture<Boolean> initEndpointGroup(EndpointGroup endpointGroup) {
        this.endpointGroup = endpointGroup;
        // Note: thread-local customizer must be run before EndpointSelector.select()
        //       so that the customizer can inject the attributes which may be required
        //       by the EndpointSelector.
        runThreadLocalContextCustomizers();
        final Endpoint endpoint = endpointGroup.selectNow(this);
        if (endpoint != null) {
            updateEndpoint(endpoint);
            acquireEventLoop(endpointGroup);
            return UnmodifiableFuture.completedFuture(true);
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
                return UnmodifiableFuture.completedFuture(success);
            } else {
                // We need to hand over to the acquired EventLoop.
                return CompletableFuture.supplyAsync(() -> success, acquiredEventLoop);
            }
        }).thenCompose(Function.identity());
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
                headersBuilder.set(HttpHeaderNames.AUTHORITY, authority);
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
        if (ctx.rpcRequest() != null) {
            requireNonNull(rpcReq, "rpcReq");
        }

        eventLoop = ctx.eventLoop().withoutContext();
        options = ctx.options();
        endpointGroup = ctx.endpointGroup();
        updateEndpoint(endpoint);
        fragment = ctx.fragment();
        root = ctx.root();

        log = RequestLog.builder(this);
        timeoutScheduler = new TimeoutScheduler(TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis()));

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
        return TimeUnit.NANOSECONDS.toMillis(timeoutScheduler.timeoutNanos());
    }

    @Override
    public void clearResponseTimeout() {
        timeoutScheduler.clearTimeout();
    }

    @Override
    public void setResponseTimeoutMillis(TimeoutMode mode, long responseTimeoutMillis) {
        timeoutScheduler.setTimeoutNanos(requireNonNull(mode, "mode"),
                                         TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis));
    }

    @Override
    public void setResponseTimeout(TimeoutMode mode, Duration responseTimeout) {
        timeoutScheduler.setTimeoutNanos(requireNonNull(mode, "mode"),
                                         requireNonNull(responseTimeout, "responseTimeout").toNanos());
    }

    @Override
    @Nullable
    public Runnable responseTimeoutHandler() {
        return responseTimeoutHandler;
    }

    @Override
    public void setResponseTimeoutHandler(Runnable responseTimeoutHandler) {
        this.responseTimeoutHandler = requireNonNull(responseTimeoutHandler, "responseTimeoutHandler");
    }

    /**
     * Sets the {@code responseTimeoutController} that is set to a new timeout when
     * the {@linkplain #responseTimeoutMillis()} response timeout} setting is changed.
     *
     * <p>Note: This method is meant for internal use by client-side protocol implementation to reschedule
     * a timeout task when a user updates the response timeout configuration.
     */
    void setResponseTimeoutController(TimeoutController responseTimeoutController) {
        timeoutScheduler.setTimeoutController(responseTimeoutController, eventLoop());
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

    @Override
    public void timeoutNow() {
        timeoutScheduler.timeoutNow();
    }

    @Override
    public boolean isTimedOut() {
        return timeoutScheduler.isTimedOut();
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
        final StringBuilder buf = TemporaryThreadLocals.get().stringBuilder();
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
