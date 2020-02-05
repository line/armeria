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
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.google.common.math.LongMath;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.UnstableApi;
import com.linecorp.armeria.internal.common.TimeoutController;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
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

    private boolean initialized;
    @Nullable
    private EventLoop eventLoop;
    private final ClientOptions options;
    @Nullable
    private EndpointGroup endpointGroup;
    @Nullable
    private Endpoint endpoint;
    @Nullable
    private final String fragment;
    @Nullable
    private final ServiceRequestContext root;

    private final RequestLogBuilder log;

    private long writeTimeoutMillis;
    private long responseTimeoutMillis;
    @Nullable
    private Runnable responseTimeoutHandler;
    @Nullable
    private TimeoutController responseTimeoutController;
    @Nullable
    private Consumer<TimeoutController> pendingTimeoutTask;
    private long maxResponseLength;

    @SuppressWarnings("FieldMayBeFinal") // Updated via `additionalRequestHeadersUpdater`
    private volatile HttpHeaders additionalRequestHeaders;

    @Nullable
    private String strVal;

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

        writeTimeoutMillis = options.writeTimeoutMillis();
        responseTimeoutMillis = options.responseTimeoutMillis();
        maxResponseLength = options.maxResponseLength();
        additionalRequestHeaders = options.getOrElse(ClientOption.HTTP_HEADERS, HttpHeaders.of());
        customizers = copyThreadLocalCustomizers();
    }

    @Nullable
    private static ServiceRequestContext serviceRequestContext() {
        final RequestContext current = RequestContext.currentOrNull();
        if (current instanceof ServiceRequestContext) {
            return (ServiceRequestContext) current;
        }
        if (current instanceof ClientRequestContext) {
            return ((ClientRequestContext) current).root();
        }
        return null;
    }

    /**
     * Initializes this context with the specified {@link EndpointGroup}.
     * This method must be invoked to finish the construction of this context.
     *
     * @return {@code true} if the initialization has succeeded.
     *         {@code false} if the initialization has failed and this context's {@link RequestLog} has been
     *         completed with the cause of the failure.
     */
    public boolean init(EndpointGroup endpointGroup) {
        assert endpoint == null : endpoint;
        assert !initialized;
        initialized = true;

        try {
            if (endpointGroup instanceof Endpoint) {
                this.endpointGroup = null;
                updateEndpoint((Endpoint) endpointGroup);
                runThreadLocalContextCustomizers();
            } else {
                this.endpointGroup = endpointGroup;
                // Note: thread-local customizer must be run before EndpointSelector.select()
                //       so that the customizer can inject the attributes which may be required
                //       by the EndpointSelector.
                runThreadLocalContextCustomizers();
                updateEndpoint(endpointGroup.select(this));
            }

            if (eventLoop == null) {
                final ReleasableHolder<EventLoop> releasableEventLoop =
                        options().factory().acquireEventLoop(endpoint, sessionProtocol());
                eventLoop = releasableEventLoop.get();
                log.whenComplete().thenAccept(unused -> releasableEventLoop.release());
            }

            return true;
        } catch (Throwable t) {
            if (eventLoop == null) {
                // Always set the eventLoop because it can be used in a decorator.
                eventLoop = CommonPools.workerGroup().next();
            }
            failEarly(t);
        }

        return false;
    }

    private void updateEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
        autoFillSchemeAndAuthority();
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

    private void failEarly(Throwable cause) {
        final RequestLogBuilder logBuilder = logBuilder();
        final UnprocessedRequestException wrapped = new UnprocessedRequestException(cause);
        logBuilder.endRequest(wrapped);
        logBuilder.endResponse(wrapped);

        final HttpRequest req = request();
        if (req != null) {
            autoFillSchemeAndAuthority();
            req.abort(cause);
        }
    }

    private void autoFillSchemeAndAuthority() {
        final HttpRequest req = request();
        if (req == null) {
            return;
        }

        final RequestHeaders headers = req.headers();
        final String authority = endpoint != null ? endpoint.authority() : "UNKNOWN";
        if (headers.scheme() == null || !authority.equals(headers.authority())) {
            unsafeUpdateRequest(req.withHeaders(headers.toBuilder()
                                                       .authority(authority)
                                                       .scheme(sessionProtocol())));
        }
    }

    /**
     * Creates a derived context.
     */
    private DefaultClientRequestContext(DefaultClientRequestContext ctx,
                                        RequestId id,
                                        @Nullable HttpRequest req,
                                        @Nullable RpcRequest rpcReq,
                                        Endpoint endpoint) {
        super(ctx.meterRegistry(), ctx.sessionProtocol(), id, ctx.method(), ctx.path(), ctx.query(),
              req, rpcReq, ctx.root());

        // The new requests cannot be null if it was previously non-null.
        if (ctx.request() != null) {
            requireNonNull(req, "req");
        }
        if (ctx.rpcRequest() != null) {
            requireNonNull(rpcReq, "rpcReq");
        }

        eventLoop = ctx.eventLoop();
        options = ctx.options();
        endpointGroup = ctx.endpointGroup();
        updateEndpoint(requireNonNull(endpoint, "endpoint"));
        fragment = ctx.fragment();
        root = ctx.root();

        log = RequestLog.builder(this);

        writeTimeoutMillis = ctx.writeTimeoutMillis();
        responseTimeoutMillis = ctx.responseTimeoutMillis();
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
                                                  Endpoint endpoint) {
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
    public EventLoop eventLoop() {
        checkState(eventLoop != null, "Should call init(endpoint) before invoking this method.");
        return eventLoop;
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
        return responseTimeoutMillis;
    }

    @Override
    public void clearResponseTimeout() {
        if (responseTimeoutMillis == 0) {
            return;
        }

        final TimeoutController responseTimeoutController = this.responseTimeoutController;
        responseTimeoutMillis = 0;
        if (responseTimeoutController != null) {
            if (eventLoop().inEventLoop()) {
                responseTimeoutController.cancelTimeout();
            } else {
                eventLoop().execute(responseTimeoutController::cancelTimeout);
            }
        } else {
            addPendingTimeoutTask(TimeoutController::cancelTimeout);
        }
    }

    @Override
    public void setResponseTimeoutMillis(long responseTimeoutMillis) {
        checkArgument(responseTimeoutMillis >= 0,
                      "responseTimeoutMillis: %s (expected: >= 0)", responseTimeoutMillis);
        if (responseTimeoutMillis == 0) {
            clearResponseTimeout();
        }

        final long adjustmentMillis =
                LongMath.saturatedSubtract(responseTimeoutMillis, this.responseTimeoutMillis);
        extendResponseTimeoutMillis(adjustmentMillis);
    }

    @Override
    public void setResponseTimeout(Duration responseTimeout) {
        setResponseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    @Override
    public void extendResponseTimeoutMillis(long adjustmentMillis) {
        if (adjustmentMillis == 0 || responseTimeoutMillis == 0) {
            return;
        }

        final long oldResponseTimeoutMillis = responseTimeoutMillis;
        responseTimeoutMillis = LongMath.saturatedAdd(oldResponseTimeoutMillis, adjustmentMillis);
        final TimeoutController responseTimeoutController = this.responseTimeoutController;
        if (responseTimeoutController != null) {
            if (eventLoop().inEventLoop()) {
                responseTimeoutController.extendTimeout(adjustmentMillis);
            } else {
                eventLoop().execute(() -> responseTimeoutController.extendTimeout(adjustmentMillis));
            }
        } else {
            addPendingTimeoutTask(timeoutController -> timeoutController.extendTimeout(adjustmentMillis));
        }
    }

    @Override
    public void extendResponseTimeout(Duration adjustment) {
        extendResponseTimeoutMillis(requireNonNull(adjustment, "adjustment").toMillis());
    }

    @Override
    public void setResponseTimeoutAfterMillis(long responseTimeoutMillis) {
        checkArgument(responseTimeoutMillis > 0,
                      "responseTimeoutMillis: %s (expected: > 0)", responseTimeoutMillis);

        long passedTimeMillis = 0;
        final TimeoutController responseTimeoutController = this.responseTimeoutController;
        if (responseTimeoutController != null) {
            final Long startTimeNanos = responseTimeoutController.startTimeNanos();
            if (startTimeNanos != null) {
                passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
            }
            if (eventLoop().inEventLoop()) {
                responseTimeoutController.resetTimeout(responseTimeoutMillis);
            } else {
                eventLoop().execute(() -> responseTimeoutController.resetTimeout(responseTimeoutMillis));
            }
        } else {
            final long startTimeNanos = System.nanoTime();
            addPendingTimeoutTask(timeoutController -> {
                final long passedTimeMillis0 =
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
                final long timeoutMillis = Math.max(1, responseTimeoutMillis - passedTimeMillis0);
                timeoutController.resetTimeout(timeoutMillis);
            });
        }

        this.responseTimeoutMillis = LongMath.saturatedAdd(passedTimeMillis, responseTimeoutMillis);
    }

    @Override
    public void setResponseTimeoutAfter(Duration responseTimeout) {
        setResponseTimeoutAfterMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    @Override
    public void setResponseTimeoutAtMillis(long responseTimeoutAtMillis) {
        checkArgument(responseTimeoutAtMillis >= 0,
                      "responseTimeoutAtMillis: %s (expected: >= 0)", responseTimeoutMillis);
        final long responseTimeoutAfter = responseTimeoutAtMillis - System.currentTimeMillis();

        if (responseTimeoutAfter <= 0) {
            final TimeoutController responseTimeoutController = this.responseTimeoutController;
            if (responseTimeoutController != null) {
                if (eventLoop().inEventLoop()) {
                    responseTimeoutController.timeoutNow();
                } else {
                    eventLoop().execute(responseTimeoutController::timeoutNow);
                }
            } else {
                addPendingTimeoutTask(TimeoutController::timeoutNow);
            }
        } else {
            setResponseTimeoutAfterMillis(responseTimeoutAfter);
        }
    }

    @Override
    public void setResponseTimeoutAt(Instant responseTimeoutAt) {
        setResponseTimeoutAtMillis(requireNonNull(responseTimeoutAt, "responseTimeoutAt").toEpochMilli());
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
        requireNonNull(responseTimeoutController, "responseTimeoutController");
        checkState(this.responseTimeoutController == null, "responseTimeoutController is set already.");
        this.responseTimeoutController = responseTimeoutController;

        // Invoke pending timeout task which was set before initializing responseTimeoutController
        final Consumer<TimeoutController> pendingTimeoutTask = this.pendingTimeoutTask;
        if (pendingTimeoutTask != null) {
            if (eventLoop().inEventLoop()) {
                pendingTimeoutTask.accept(responseTimeoutController);
            } else {
                eventLoop().execute(() -> pendingTimeoutTask.accept(responseTimeoutController));
            }
        }
    }

    private void addPendingTimeoutTask(Consumer<TimeoutController> pendingTimeoutTask) {
        if (this.pendingTimeoutTask == null) {
            this.pendingTimeoutTask = pendingTimeoutTask;
        } else {
            this.pendingTimeoutTask = this.pendingTimeoutTask.andThen(pendingTimeoutTask);
        }
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
    public RequestLogAccess log() {
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

        final StringBuilder buf = new StringBuilder(107);
        buf.append("[C]");

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
