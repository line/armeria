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
package com.linecorp.armeria.common.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.ChannelUtil;
import com.linecorp.armeria.internal.TemporaryThreadLocals;

import io.netty.channel.Channel;

/**
 * Default {@link RequestLog} implementation.
 */
public class DefaultRequestLog implements RequestLog, RequestLogBuilder {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRequestLog.class);

    private static final AtomicIntegerFieldUpdater<DefaultRequestLog> flagsUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRequestLog.class, "flags");

    private static final AtomicIntegerFieldUpdater<DefaultRequestLog> deferredFlagsUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRequestLog.class, "deferredFlags");

    private static final RequestHeaders DUMMY_REQUEST_HEADERS_HTTP =
            RequestHeaders.builder(HttpMethod.UNKNOWN, "?").scheme("http").authority("?").build();
    private static final RequestHeaders DUMMY_REQUEST_HEADERS_HTTPS =
            RequestHeaders.builder(HttpMethod.UNKNOWN, "?").scheme("https").authority("?").build();
    private static final ResponseHeaders DUMMY_RESPONSE_HEADERS = ResponseHeaders.of(HttpStatus.UNKNOWN);

    private static boolean warnedSettingContentPreviewTwice;

    private final RequestContext ctx;
    private final CompleteRequestLog notCheckingAccessor = new CompleteRequestLog();

    @Nullable
    private List<RequestLogAccess> children;
    private boolean hasLastChild;

    /**
     * Updated by {@link #flagsUpdater}.
     */
    private volatile int flags;
    /**
     * Updated by {@link #deferredFlagsUpdater}.
     */
    private volatile int deferredFlags;
    private final List<RequestLogFuture> pendingFutures = new ArrayList<>(4);
    @Nullable
    private UnmodifiableFuture<RequestLog> partiallyCompletedFuture;
    @Nullable
    private UnmodifiableFuture<RequestLog> completedFuture;

    private long requestStartTimeMicros;
    private long requestStartTimeNanos;
    private boolean requestFirstBytesTransferredTimeNanosSet;
    private long requestFirstBytesTransferredTimeNanos;
    private long requestEndTimeNanos;
    private long requestLength;
    @Nullable
    private String requestContentPreview;
    @Nullable
    private Throwable requestCause;

    private long responseStartTimeMicros;
    private long responseStartTimeNanos;
    private boolean responseFirstBytesTransferredTimeNanosSet;
    private long responseFirstBytesTransferredTimeNanos;
    private long responseEndTimeNanos;
    private long responseLength;
    @Nullable
    private String responseContentPreview;
    @Nullable
    private Throwable responseCause;

    @Nullable
    private Channel channel;
    @Nullable
    private SSLSession sslSession;
    @Nullable
    private SessionProtocol sessionProtocol;
    @Nullable
    private ClientConnectionTimings connectionTimings;
    private SerializationFormat serializationFormat = SerializationFormat.NONE;
    @Nullable
    private Scheme scheme;
    @Nullable
    private String name;

    private RequestHeaders requestHeaders = DUMMY_REQUEST_HEADERS_HTTP;
    private HttpHeaders requestTrailers = HttpHeaders.of();

    private ResponseHeaders responseHeaders = DUMMY_RESPONSE_HEADERS;
    private HttpHeaders responseTrailers = HttpHeaders.of();

    @Nullable
    private Object requestContent;
    @Nullable
    private Object rawRequestContent;
    @Nullable
    private Object responseContent;
    @Nullable
    private Object rawResponseContent;

    private volatile int requestStrFlags = -1;
    private volatile int responseStrFlags = -1;
    @Nullable
    private String requestStr;
    @Nullable
    private String responseStr;

    /**
     * Creates a new instance.
     */
    public DefaultRequestLog(RequestContext ctx) {
        this.ctx = requireNonNull(ctx, "ctx");
    }

    // Methods from RequestLogAccess

    @Override
    public boolean isComplete() {
        return isComplete(flags);
    }

    private static boolean isComplete(int flags) {
        return flags == RequestLogProperty.FLAGS_ALL_COMPLETE;
    }

    @Override
    public boolean isRequestComplete() {
        return isAvailable(flags, RequestLogProperty.FLAGS_REQUEST_COMPLETE);
    }

    @Override
    public boolean isAvailable(RequestLogProperty property) {
        return isAvailable(flags, property.flag());
    }

    private boolean isAvailable(int interestedFlags) {
        return isAvailable(flags, interestedFlags);
    }

    private static boolean isAvailable(int flags, RequestLogProperty property) {
        return isAvailable(flags, property.flag());
    }

    private static boolean isAvailable(int flags, int interestedFlags) {
        return (flags & interestedFlags) == interestedFlags;
    }

    @Override
    public RequestLog partial() {
        return partial(flags);
    }

    private RequestLog partial(int flags) {
        return isComplete(flags) ? notCheckingAccessor : this;
    }

    @Override
    public CompletableFuture<RequestLog> whenComplete() {
        return future(RequestLogProperty.FLAGS_ALL_COMPLETE);
    }

    @Override
    public CompletableFuture<RequestOnlyLog> whenRequestComplete() {
        return future(RequestLogProperty.FLAGS_REQUEST_COMPLETE);
    }

    @Override
    public CompletableFuture<RequestLog> whenAvailable(RequestLogProperty property) {
        return future(requireNonNull(property, "property").flag());
    }

    @Override
    public CompletableFuture<RequestLog> whenAvailable(RequestLogProperty... properties) {
        return future(RequestLogProperty.flags(requireNonNull(properties, "properties")));
    }

    @Override
    public CompletableFuture<RequestLog> whenAvailable(Iterable<RequestLogProperty> properties) {
        return future(RequestLogProperty.flags(requireNonNull(properties, "properties")));
    }

    @Override
    public RequestLog ensureComplete() {
        if (!isComplete()) {
            throw new RequestLogAvailabilityException(RequestLog.class.getSimpleName() + " not complete");
        }
        return notCheckingAccessor;
    }

    @Override
    public RequestOnlyLog ensureRequestComplete() {
        if (!isRequestComplete()) {
            throw new RequestLogAvailabilityException(RequestOnlyLog.class.getSimpleName() + " not complete");
        }
        return this;
    }

    @Override
    public RequestLog ensureAvailable(RequestLogProperty property) {
        if (!isAvailable(property)) {
            throw new RequestLogAvailabilityException(property.name());
        }
        return this;
    }

    @Override
    public RequestLog ensureAvailable(RequestLogProperty... properties) {
        if (!isAvailable(properties)) {
            throw new RequestLogAvailabilityException(Arrays.toString(properties));
        }
        return this;
    }

    @Override
    public RequestLog ensureAvailable(Iterable<RequestLogProperty> properties) {
        if (!isAvailable(properties)) {
            throw new RequestLogAvailabilityException(properties.toString());
        }
        return this;
    }

    @Override
    public int availabilityStamp() {
        return flags;
    }

    @Override
    public RequestContext context() {
        return ctx;
    }

    @Override
    public List<RequestLogAccess> children() {
        return children != null ? ImmutableList.copyOf(children) : ImmutableList.of();
    }

    // Methods required for updating availability and notifying listeners.

    private <T extends RequestOnlyLog> CompletableFuture<T> future(int interestedFlags) {
        if (interestedFlags == 0) {
            throw new IllegalArgumentException("no availability specified");
        }

        final CompletableFuture<RequestLog> future;

        final int flags = this.flags;

        if (isAvailable(flags, interestedFlags)) {
            future = completedFuture(flags);
        } else {
            final RequestLogFuture[] satisfiedFutures;
            final RequestLogFuture newFuture = new RequestLogFuture(interestedFlags);
            synchronized (pendingFutures) {
                pendingFutures.add(newFuture);
                satisfiedFutures = removeSatisfiedFutures();
            }
            if (satisfiedFutures != null) {
                completeSatisfiedFutures(satisfiedFutures, partial(flags));
            }

            future = newFuture;
        }

        @SuppressWarnings("unchecked")
        final CompletableFuture<T> cast = (CompletableFuture<T>) future;
        return cast;
    }

    private UnmodifiableFuture<RequestLog> completedFuture(int flags) {
        if (isComplete(flags)) {
            if (completedFuture == null) {
                completedFuture = UnmodifiableFuture.completedFuture(notCheckingAccessor);
            }
            return completedFuture;
        }

        if (partiallyCompletedFuture == null) {
            partiallyCompletedFuture = UnmodifiableFuture.completedFuture(this);
        }
        return partiallyCompletedFuture;
    }

    private void updateFlags(RequestLogProperty property) {
        updateFlags(property.flag());
    }

    private void updateFlags(int flags) {
        for (;;) {
            final int oldFlags = this.flags;
            final int newFlags = oldFlags | flags;
            if (oldFlags == newFlags) {
                break;
            }

            if (flagsUpdater.compareAndSet(this, oldFlags, newFlags)) {
                final RequestLogFuture[] satisfiedFutures;
                synchronized (pendingFutures) {
                    satisfiedFutures = removeSatisfiedFutures();
                }
                if (satisfiedFutures != null) {
                    final RequestLog log = partial(newFlags);
                    completeSatisfiedFutures(satisfiedFutures, log);
                }
                break;
            }
        }
    }

    private static void completeSatisfiedFutures(RequestLogFuture[] satisfiedFutures, RequestLog log) {
        for (RequestLogFuture f : satisfiedFutures) {
            if (f == null) {
                break;
            }
            f.completeLog(log);
        }
    }

    @Nullable
    private RequestLogFuture[] removeSatisfiedFutures() {
        if (pendingFutures.isEmpty()) {
            return null;
        }

        final int flags = this.flags;
        final int maxNumListeners = pendingFutures.size();
        final Iterator<RequestLogFuture> i = pendingFutures.iterator();
        RequestLogFuture[] satisfied = null;
        int numSatisfied = 0;

        do {
            final RequestLogFuture e = i.next();
            final int interestedFlags = e.interestedFlags;
            if ((flags & interestedFlags) == interestedFlags) {
                i.remove();
                if (satisfied == null) {
                    satisfied = new RequestLogFuture[maxNumListeners];
                }
                satisfied[numSatisfied++] = e;
            }
        } while (i.hasNext());

        return satisfied;
    }

    private void updateDeferredFlags(RequestLogProperty property) {
        final int flag = property.flag();
        for (;;) {
            final int oldFlags = deferredFlags;
            final int newFlags = oldFlags | flag;
            if (oldFlags == newFlags) {
                break;
            }

            if (deferredFlagsUpdater.compareAndSet(this, oldFlags, newFlags)) {
                break;
            }
        }
    }

    // Methods required for adding children.

    @Override
    public void addChild(RequestLogAccess child) {
        checkState(!hasLastChild, "last child is already added");
        requireNonNull(child, "child");
        if (children == null) {
            // first child's all request-side logging events are propagated immediately to the parent
            children = new ArrayList<>();
            propagateRequestSideLog(child);
        }
        children.add(child);
    }

    private void propagateRequestSideLog(RequestLogAccess child) {
        // Update the available properties always by adding a callback,
        // because the child's properties will never be available immediately.
        child.whenAvailable(RequestLogProperty.REQUEST_START_TIME)
             .thenAccept(log -> startRequest(log.requestStartTimeNanos(), log.requestStartTimeMicros()));
        child.whenAvailable(RequestLogProperty.SESSION)
             .thenAccept(log -> session(log.channel(), log.sessionProtocol(),
                                        log.sslSession(), log.connectionTimings()));
        child.whenAvailable(RequestLogProperty.SCHEME)
             .thenAccept(log -> serializationFormat(log.scheme().serializationFormat()));
        child.whenAvailable(RequestLogProperty.NAME)
             .thenAccept(log -> {
                 final String name = log.name();
                 if (name != null) {
                     name(name);
                 }
             });
        child.whenAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)
             .thenAccept(log -> {
                 final Long timeNanos = log.requestFirstBytesTransferredTimeNanos();
                 if (timeNanos != null) {
                     requestFirstBytesTransferred(timeNanos);
                 }
             });
        child.whenAvailable(RequestLogProperty.REQUEST_HEADERS)
             .thenAccept(log -> requestHeaders(log.requestHeaders()));
        child.whenAvailable(RequestLogProperty.REQUEST_CONTENT)
             .thenAccept(log -> requestContent(log.requestContent(), log.rawRequestContent()));
        child.whenRequestComplete().thenAccept(log -> {
            requestLength(log.requestLength());
            requestContentPreview(log.requestContentPreview());
            requestTrailers(log.requestTrailers());
            endRequest0(log.requestCause(), log.requestEndTimeNanos());
        });
    }

    @Override
    public void endResponseWithLastChild() {
        checkState(!hasLastChild, "last child is already added");
        checkState(children != null && !children.isEmpty(), "at least one child should be already added");
        hasLastChild = true;
        final RequestLogAccess lastChild = children.get(children.size() - 1);
        propagateResponseSideLog(lastChild.partial());
    }

    private void propagateResponseSideLog(RequestLog lastChild) {
        // Update the available properties without adding a callback if the lastChild already has them.
        if (lastChild.isAvailable(RequestLogProperty.RESPONSE_START_TIME)) {
            startResponse0(lastChild.responseStartTimeNanos(), lastChild.responseStartTimeMicros(), true);
        } else {
            lastChild.whenAvailable(RequestLogProperty.RESPONSE_START_TIME)
                     .thenAccept(log -> startResponse0(log.responseStartTimeNanos(),
                                                       log.responseStartTimeMicros(), true));
        }

        if (lastChild.isAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)) {
            final Long timeNanos = lastChild.responseFirstBytesTransferredTimeNanos();
            if (timeNanos != null) {
                responseFirstBytesTransferred(timeNanos);
            }
        } else {
            lastChild.whenAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)
                     .thenAccept(log -> {
                         final Long timeNanos = log.responseFirstBytesTransferredTimeNanos();
                         if (timeNanos != null) {
                             responseFirstBytesTransferred(timeNanos);
                         }
                     });
        }

        if (lastChild.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            responseHeaders(lastChild.responseHeaders());
        } else {
            lastChild.whenAvailable(RequestLogProperty.RESPONSE_HEADERS)
                     .thenAccept(log -> responseHeaders(log.responseHeaders()));
        }

        if (lastChild.isAvailable(RequestLogProperty.RESPONSE_CONTENT)) {
            responseContent(lastChild.responseContent(), lastChild.rawResponseContent());
        } else {
            lastChild.whenAvailable(RequestLogProperty.RESPONSE_CONTENT)
                     .thenAccept(log -> responseContent(log.responseContent(), log.rawResponseContent()));
        }

        if (lastChild.isComplete()) {
            propagateResponseEndData(lastChild);
        } else {
            lastChild.whenComplete().thenAccept(this::propagateResponseEndData);
        }
    }

    private void propagateResponseEndData(RequestLog log) {
        responseLength(log.responseLength());
        responseContentPreview(log.responseContentPreview());
        responseTrailers(log.responseTrailers());
        endResponse0(log.responseCause(), log.responseEndTimeNanos());
    }

    // Request-side methods.

    @Override
    public void startRequest(long requestStartTimeNanos, long requestStartTimeMicros) {
        if (!isAvailable(RequestLogProperty.REQUEST_START_TIME)) {
            this.requestStartTimeNanos = requestStartTimeNanos;
            this.requestStartTimeMicros = requestStartTimeMicros;
        }

        updateFlags(RequestLogProperty.REQUEST_START_TIME);
    }

    @Override
    public long requestStartTimeMicros() {
        ensureAvailable(RequestLogProperty.REQUEST_START_TIME);
        return requestStartTimeMicros;
    }

    @Override
    public long requestStartTimeMillis() {
        return TimeUnit.MICROSECONDS.toMillis(requestStartTimeMicros());
    }

    @Override
    public long requestStartTimeNanos() {
        ensureAvailable(RequestLogProperty.REQUEST_START_TIME);
        return requestStartTimeNanos;
    }

    @Override
    public Long requestFirstBytesTransferredTimeNanos() {
        ensureAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME);
        return requestFirstBytesTransferredTimeNanosSet ? requestFirstBytesTransferredTimeNanos : null;
    }

    @Override
    public long requestEndTimeNanos() {
        ensureAvailable(RequestLogProperty.REQUEST_END_TIME);
        return requestEndTimeNanos;
    }

    @Override
    public long requestDurationNanos() {
        ensureAvailable(RequestLogProperty.REQUEST_END_TIME);
        return requestEndTimeNanos - requestStartTimeNanos;
    }

    @Override
    public Throwable requestCause() {
        ensureAvailable(RequestLogProperty.REQUEST_CAUSE);
        return requestCause;
    }

    @Override
    public void session(@Nullable Channel channel, SessionProtocol sessionProtocol,
                        @Nullable ClientConnectionTimings connectionTimings) {
        if (isAvailable(RequestLogProperty.SESSION)) {
            return;
        }

        session0(channel, requireNonNull(sessionProtocol, "sessionProtocol"),
                 ChannelUtil.findSslSession(channel, sessionProtocol),
                 connectionTimings);
    }

    @Override
    public void session(@Nullable Channel channel, SessionProtocol sessionProtocol,
                        @Nullable SSLSession sslSession, @Nullable ClientConnectionTimings connectionTimings) {
        if (isAvailable(RequestLogProperty.SESSION)) {
            return;
        }

        session0(channel, requireNonNull(sessionProtocol, "sessionProtocol"), sslSession, connectionTimings);
    }

    private void session0(@Nullable Channel channel, SessionProtocol sessionProtocol,
                          @Nullable SSLSession sslSession,
                          @Nullable ClientConnectionTimings connectionTimings) {

        this.channel = channel;
        this.sslSession = sslSession;
        this.sessionProtocol = sessionProtocol;
        this.connectionTimings = connectionTimings;
        if (sessionProtocol.isTls()) {
            // Switch to the dummy headers with ':scheme=https' if the connection is TLS.
            if (requestHeaders == DUMMY_REQUEST_HEADERS_HTTP) {
                requestHeaders = DUMMY_REQUEST_HEADERS_HTTPS;
            }
        }

        updateFlags(RequestLogProperty.SESSION);
    }

    @Override
    public Channel channel() {
        ensureAvailable(RequestLogProperty.SESSION);
        return channel;
    }

    @Override
    public SSLSession sslSession() {
        ensureAvailable(RequestLogProperty.SESSION);
        return sslSession;
    }

    @Override
    public SessionProtocol sessionProtocol() {
        ensureAvailable(RequestLogProperty.SESSION);
        assert sessionProtocol != null;
        return sessionProtocol;
    }

    @Nullable
    @Override
    public ClientConnectionTimings connectionTimings() {
        ensureAvailable(RequestLogProperty.SESSION);
        return connectionTimings;
    }

    @Override
    public void serializationFormat(SerializationFormat serializationFormat) {
        if (isAvailable(RequestLogProperty.SCHEME) || this.serializationFormat != SerializationFormat.NONE) {
            return;
        }

        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        if (sessionProtocol != null) {
            scheme = Scheme.of(serializationFormat, sessionProtocol);
            updateFlags(RequestLogProperty.SCHEME);
        }
    }

    @Override
    public Scheme scheme() {
        ensureAvailable(RequestLogProperty.SCHEME);
        assert scheme != null;
        return scheme;
    }

    @Override
    @Nullable
    public String name() {
        ensureAvailable(RequestLogProperty.NAME);
        return name;
    }

    @Override
    public void name(String name) {
        requireNonNull(name, "name");
        checkArgument(!name.isEmpty(), "name is empty.");
        if (isAvailable(RequestLogProperty.NAME)) {
            return;
        }

        this.name = name;
        updateFlags(RequestLogProperty.NAME);
    }

    @Override
    public long requestLength() {
        ensureAvailable(RequestLogProperty.REQUEST_LENGTH);
        return requestLength;
    }

    @Override
    public void requestLength(long requestLength) {
        if (requestLength < 0) {
            throw new IllegalArgumentException("requestLength: " + requestLength + " (expected: >= 0)");
        }

        if (isAvailable(RequestLogProperty.REQUEST_LENGTH)) {
            return;
        }

        this.requestLength = requestLength;
        updateFlags(RequestLogProperty.REQUEST_LENGTH);
    }

    @Override
    public void requestFirstBytesTransferred() {
        if (isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            return;
        }
        requestFirstBytesTransferred0(System.nanoTime());
    }

    @Override
    public void requestFirstBytesTransferred(long requestFirstBytesTransferredTimeNanos) {
        if (isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            return;
        }
        requestFirstBytesTransferred0(requestFirstBytesTransferredTimeNanos);
    }

    private void requestFirstBytesTransferred0(long requestFirstBytesTransferredTimeNanos) {
        this.requestFirstBytesTransferredTimeNanos = requestFirstBytesTransferredTimeNanos;
        requestFirstBytesTransferredTimeNanosSet = true;
        updateFlags(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME);
    }

    @Override
    public void increaseRequestLength(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("deltaBytes: " + deltaBytes + " (expected: >= 0)");
        }

        if (isAvailable(RequestLogProperty.REQUEST_LENGTH)) {
            return;
        }

        requestLength += deltaBytes;
    }

    @Override
    public void increaseRequestLength(HttpData data) {
        requireNonNull(data, "data");
        increaseRequestLength(data.length());
    }

    @Override
    public RequestHeaders requestHeaders() {
        ensureAvailable(RequestLogProperty.REQUEST_HEADERS);
        return requestHeaders;
    }

    @Override
    public void requestHeaders(RequestHeaders requestHeaders) {
        if (isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
            return;
        }

        this.requestHeaders = requireNonNull(requestHeaders, "requestHeaders");
        updateFlags(RequestLogProperty.REQUEST_HEADERS);
    }

    @Override
    public Object requestContent() {
        ensureAvailable(RequestLogProperty.REQUEST_CONTENT);
        return requestContent;
    }

    @Override
    public void requestContent(@Nullable Object requestContent, @Nullable Object rawRequestContent) {
        if (isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
            return;
        }

        this.requestContent = requestContent;
        this.rawRequestContent = rawRequestContent;
        updateFlags(RequestLogProperty.REQUEST_CONTENT);

        if (requestContent instanceof RpcRequest && ctx.rpcRequest() == null) {
            ctx.updateRpcRequest((RpcRequest) requestContent);
        }
    }

    @Override
    public Object rawRequestContent() {
        ensureAvailable(RequestLogProperty.REQUEST_CONTENT);
        return rawRequestContent;
    }

    @Override
    public String requestContentPreview() {
        ensureAvailable(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
        return requestContentPreview;
    }

    @Override
    public void requestContentPreview(@Nullable String requestContentPreview) {
        if (isAvailable(RequestLogProperty.REQUEST_CONTENT_PREVIEW)) {
            if (!warnedSettingContentPreviewTwice && requestContentPreview != null) {
                warnedSettingContentPreviewTwice = true;
                logger.warn("You tried to set the content preview twice: {} " +
                            " Did you apply content previewing decorator more than once?",
                            requestContentPreview);
            }
            return;
        }
        this.requestContentPreview = requestContentPreview;
        updateFlags(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
    }

    @Override
    public void deferRequestContent() {
        if (isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
            return;
        }
        updateDeferredFlags(RequestLogProperty.REQUEST_CONTENT);
    }

    @Override
    public void deferRequestContentPreview() {
        if (isAvailable(RequestLogProperty.REQUEST_CONTENT_PREVIEW)) {
            return;
        }
        updateDeferredFlags(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
    }

    @Override
    public HttpHeaders requestTrailers() {
        ensureAvailable(RequestLogProperty.REQUEST_TRAILERS);
        return requestTrailers;
    }

    @Override
    public void requestTrailers(HttpHeaders requestTrailers) {
        if (isAvailable(RequestLogProperty.REQUEST_TRAILERS)) {
            return;
        }
        requireNonNull(requestTrailers, "requestTrailers");
        this.requestTrailers = requestTrailers;
        updateFlags(RequestLogProperty.REQUEST_TRAILERS);
    }

    @Override
    public void endRequest() {
        endRequest0(null);
    }

    @Override
    public void endRequest(Throwable requestCause) {
        endRequest0(requireNonNull(requestCause, "requestCause"));
    }

    @Override
    public void endRequest(long requestEndTimeNanos) {
        endRequest0(null, requestEndTimeNanos);
    }

    @Override
    public void endRequest(Throwable requestCause, long requestEndTimeNanos) {
        endRequest0(requireNonNull(requestCause, "requestCause"), requestEndTimeNanos);
    }

    private void endRequest0(@Nullable Throwable requestCause) {
        endRequest0(requestCause, System.nanoTime());
    }

    private void endRequest0(@Nullable Throwable requestCause, long requestEndTimeNanos) {
        final int flags;
        if (requestCause != null) {
            flags = RequestLogProperty.FLAGS_REQUEST_COMPLETE;
        } else {
            flags = RequestLogProperty.FLAGS_REQUEST_COMPLETE & ~deferredFlags;
        }

        if (isAvailable(flags)) {
            return;
        }

        // if the request is not started yet, call startRequest() with requestEndTimeNanos so that
        // totalRequestDuration will be 0
        startRequest(requestEndTimeNanos, SystemInfo.currentTimeMicros());
        session(null, context().sessionProtocol(), null, null);

        if (scheme == null) {
            assert sessionProtocol != null;
            scheme = Scheme.of(serializationFormat, sessionProtocol);
        }

        if (name == null) {
            final RpcRequest rpcReq = ctx.rpcRequest();
            if (rpcReq != null) {
                name = rpcReq.method();
            } else if (requestContent instanceof RpcRequest) {
                name = ((RpcRequest) requestContent).method();
            }
        }
        this.requestEndTimeNanos = requestEndTimeNanos;
        this.requestCause = requestCause;
        updateFlags(flags);
    }

    // Response-side methods.

    @Override
    public void startResponse() {
        startResponse0(true);
    }

    @Override
    public void startResponse(long responseStartTimeNanos, long responseStartTimeMicros) {
        startResponse0(responseStartTimeNanos, responseStartTimeMicros, true);
    }

    private void startResponse0(boolean updateFlags) {
        startResponse0(System.nanoTime(), SystemInfo.currentTimeMicros(), updateFlags);
    }

    private void startResponse0(long responseStartTimeNanos, long responseStartTimeMicros,
                                boolean updateFlags) {
        if (isAvailable(RequestLogProperty.RESPONSE_START_TIME)) {
            return;
        }

        this.responseStartTimeNanos = responseStartTimeNanos;
        this.responseStartTimeMicros = responseStartTimeMicros;
        if (updateFlags) {
            updateFlags(RequestLogProperty.RESPONSE_START_TIME);
        }
    }

    @Override
    public long responseStartTimeMicros() {
        ensureAvailable(RequestLogProperty.RESPONSE_START_TIME);
        return responseStartTimeMicros;
    }

    @Override
    public long responseStartTimeMillis() {
        return TimeUnit.MICROSECONDS.toMillis(responseStartTimeMicros());
    }

    @Override
    public long responseStartTimeNanos() {
        ensureAvailable(RequestLogProperty.RESPONSE_START_TIME);
        return responseStartTimeNanos;
    }

    @Override
    public Long responseFirstBytesTransferredTimeNanos() {
        ensureAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME);
        return responseFirstBytesTransferredTimeNanosSet ? responseFirstBytesTransferredTimeNanos : null;
    }

    @Override
    public long responseEndTimeNanos() {
        ensureAvailable(RequestLogProperty.RESPONSE_END_TIME);
        return responseEndTimeNanos;
    }

    @Override
    public long responseDurationNanos() {
        ensureAvailable(RequestLogProperty.RESPONSE_END_TIME);
        return responseEndTimeNanos - responseStartTimeNanos;
    }

    @Override
    public long totalDurationNanos() {
        ensureAvailable(RequestLogProperty.RESPONSE_END_TIME);
        return responseEndTimeNanos - requestStartTimeNanos;
    }

    @Override
    public Throwable responseCause() {
        ensureAvailable(RequestLogProperty.RESPONSE_CAUSE);
        return responseCause;
    }

    @Override
    public long responseLength() {
        ensureAvailable(RequestLogProperty.RESPONSE_LENGTH);
        return responseLength;
    }

    @Override
    public void responseLength(long responseLength) {
        if (responseLength < 0) {
            throw new IllegalArgumentException("responseLength: " + responseLength + " (expected: >= 0)");
        }

        if (isAvailable(RequestLogProperty.RESPONSE_LENGTH)) {
            return;
        }

        this.responseLength = responseLength;
    }

    @Override
    public void responseFirstBytesTransferred() {
        if (isAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)) {
            return;
        }
        responseFirstBytesTransferred0(System.nanoTime());
    }

    @Override
    public void responseFirstBytesTransferred(long responseFirstBytesTransferredTimeNanos) {
        if (isAvailable(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME)) {
            return;
        }
        responseFirstBytesTransferred0(responseFirstBytesTransferredTimeNanos);
    }

    private void responseFirstBytesTransferred0(long responseFirstBytesTransferredTimeNanos) {
        this.responseFirstBytesTransferredTimeNanos = responseFirstBytesTransferredTimeNanos;
        responseFirstBytesTransferredTimeNanosSet = true;
        updateFlags(RequestLogProperty.RESPONSE_FIRST_BYTES_TRANSFERRED_TIME);
    }

    @Override
    public void increaseResponseLength(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("deltaBytes: " + deltaBytes + " (expected: >= 0)");
        }

        if (isAvailable(RequestLogProperty.RESPONSE_LENGTH)) {
            return;
        }

        responseLength += deltaBytes;
    }

    @Override
    public void increaseResponseLength(HttpData data) {
        requireNonNull(data, "data");
        increaseResponseLength(data.length());
    }

    @Override
    public ResponseHeaders responseHeaders() {
        ensureAvailable(RequestLogProperty.RESPONSE_HEADERS);
        return responseHeaders;
    }

    @Override
    public void responseHeaders(ResponseHeaders responseHeaders) {
        if (isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            return;
        }

        this.responseHeaders = requireNonNull(responseHeaders, "responseHeaders");
        updateFlags(RequestLogProperty.RESPONSE_HEADERS);
    }

    @Override
    public Object responseContent() {
        ensureAvailable(RequestLogProperty.RESPONSE_CONTENT);
        return responseContent;
    }

    @Override
    public void responseContent(@Nullable Object responseContent, @Nullable Object rawResponseContent) {
        if (isAvailable(RequestLogProperty.RESPONSE_CONTENT)) {
            return;
        }

        if (responseContent instanceof RpcResponse) {
            final RpcResponse rpcResponse = (RpcResponse) responseContent;
            if (!rpcResponse.isDone()) {
                throw new IllegalArgumentException("responseContent must be complete: " + responseContent);
            }
            if (rpcResponse.cause() != null) {
                responseCause = rpcResponse.cause();
            }
        }

        this.responseContent = responseContent;
        this.rawResponseContent = rawResponseContent;
        updateFlags(RequestLogProperty.RESPONSE_CONTENT);
    }

    @Override
    public String responseContentPreview() {
        ensureAvailable(RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
        return responseContentPreview;
    }

    @Override
    public void responseContentPreview(@Nullable String responseContentPreview) {
        if (isAvailable(RequestLogProperty.RESPONSE_CONTENT_PREVIEW)) {
            if (!warnedSettingContentPreviewTwice && responseContentPreview != null) {
                warnedSettingContentPreviewTwice = true;
                logger.warn("You tried to set the content preview twice: {} " +
                            " Did you apply content previewing decorator more than once?",
                            responseContentPreview);
            }
            return;
        }
        this.responseContentPreview = responseContentPreview;
        updateFlags(RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
    }

    @Override
    public Object rawResponseContent() {
        ensureAvailable(RequestLogProperty.RESPONSE_CONTENT);
        return rawResponseContent;
    }

    @Override
    public void deferResponseContent() {
        if (isAvailable(RequestLogProperty.RESPONSE_CONTENT)) {
            return;
        }
        updateDeferredFlags(RequestLogProperty.RESPONSE_CONTENT);
    }

    @Override
    public void deferResponseContentPreview() {
        if (isAvailable(RequestLogProperty.RESPONSE_CONTENT_PREVIEW)) {
            return;
        }
        updateDeferredFlags(RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
    }

    @Override
    public HttpHeaders responseTrailers() {
        ensureAvailable(RequestLogProperty.RESPONSE_TRAILERS);
        return responseTrailers;
    }

    @Override
    public void responseTrailers(HttpHeaders responseTrailers) {
        if (isAvailable(RequestLogProperty.RESPONSE_TRAILERS)) {
            return;
        }

        requireNonNull(responseTrailers, "responseTrailers");
        this.responseTrailers = responseTrailers;
        updateFlags(RequestLogProperty.RESPONSE_TRAILERS);
    }

    @Override
    public void endResponse() {
        endResponse0(responseContent instanceof RpcResponse ? ((RpcResponse) responseContent).cause() : null);
    }

    @Override
    public void endResponse(Throwable responseCause) {
        endResponse0(requireNonNull(responseCause, "responseCause"));
    }

    @Override
    public void endResponse(long responseEndTimeNanos) {
        endResponse0(null, responseEndTimeNanos);
    }

    @Override
    public void endResponse(Throwable responseCause, long responseEndTimeNanos) {
        endResponse0(requireNonNull(responseCause, "responseCause"), responseEndTimeNanos);
    }

    private void endResponse0(@Nullable Throwable responseCause) {
        endResponse0(responseCause, System.nanoTime());
    }

    private void endResponse0(@Nullable Throwable responseCause, long responseEndTimeNanos) {
        final int flags;
        if (responseCause != null) {
            flags = RequestLogProperty.FLAGS_RESPONSE_COMPLETE;
        } else {
            flags = RequestLogProperty.FLAGS_RESPONSE_COMPLETE & ~deferredFlags;
        }

        if (isAvailable(flags)) {
            return;
        }

        // if the response is not started yet, call startResponse() with responseEndTimeNanos so that
        // totalResponseDuration will be 0
        startResponse0(responseEndTimeNanos, SystemInfo.currentTimeMicros(), false);

        this.responseEndTimeNanos = responseEndTimeNanos;
        if (this.responseCause == null) {
            this.responseCause = responseCause;
        }
        updateFlags(flags);
    }

    @Override
    public String toString() {
        final String req = toStringRequestOnly();
        final String res = toStringResponseOnly();

        // Create a StringBuilder with the initial capacity not considering the children's log length.
        // The children will be empty in most of the cases, so it is OK.
        final StringBuilder buf = new StringBuilder(5 + req.length() + 6 + res.length() + 1);
        buf.append("{req=")  // 5 chars
           .append(req)
           .append(", res=") // 6 chars
           .append(res)
           .append('}');     // 1 char
        final int numChildren = children != null ? children.size() : 0;
        if (numChildren > 0) {
            buf.append(", {");
            for (int i = 0; i < numChildren; i++) {
                buf.append('[');
                buf.append(children.get(i));
                buf.append(']');
                if (i != numChildren - 1) {
                    buf.append(", ");
                }
            }
            buf.append('}');
        }
        return buf.toString();
    }

    @Override
    public String toStringRequestOnly(Function<? super RequestHeaders, ?> headersSanitizer,
                                      Function<Object, ?> contentSanitizer,
                                      Function<? super HttpHeaders, ?> trailersSanitizer) {
        requireNonNull(headersSanitizer, "headersSanitizer");
        requireNonNull(contentSanitizer, "contentSanitizer");
        requireNonNull(trailersSanitizer, "trailersSanitizer");

        // Only interested in the bits related with request.
        final int flags = this.flags & RequestLogProperty.FLAGS_REQUEST_COMPLETE;
        if (requestStrFlags == flags) {
            assert requestStr != null;
            return requestStr;
        }

        if (!isAvailable(flags, RequestLogProperty.REQUEST_START_TIME)) {
            requestStr = "{}";
            requestStrFlags = flags;
            return requestStr;
        }

        final String requestCauseString;
        if (isAvailable(flags, RequestLogProperty.REQUEST_CAUSE) && requestCause != null) {
            requestCauseString = String.valueOf(requestCause);
        } else {
            requestCauseString = null;
        }

        final String sanitizedHeaders;
        if (isAvailable(flags, RequestLogProperty.REQUEST_HEADERS)) {
            sanitizedHeaders = sanitize(headersSanitizer, requestHeaders);
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (isAvailable(flags, RequestLogProperty.REQUEST_CONTENT) && requestContent != null) {
            sanitizedContent = sanitize(contentSanitizer, requestContent);
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (!requestTrailers.isEmpty()) {
            sanitizedTrailers = sanitize(trailersSanitizer, requestTrailers);
        } else {
            sanitizedTrailers = null;
        }

        final StringBuilder buf = TemporaryThreadLocals.get().stringBuilder();
        buf.append("{startTime=");
        TextFormatter.appendEpochMicros(buf, requestStartTimeMicros());

        if (isAvailable(flags, RequestLogProperty.REQUEST_LENGTH)) {
            buf.append(", length=");
            TextFormatter.appendSize(buf, requestLength);
        }

        if (isAvailable(flags, RequestLogProperty.REQUEST_END_TIME)) {
            buf.append(", duration=");
            TextFormatter.appendElapsed(buf, requestDurationNanos());
        }

        if (requestCauseString != null) {
            buf.append(", cause=").append(requestCauseString);
        }

        buf.append(", scheme=");
        if (isAvailable(flags, RequestLogProperty.SCHEME)) {
            buf.append(scheme().uriText());
        } else {
            buf.append(SerializationFormat.UNKNOWN.uriText())
               .append('+')
               .append(sessionProtocol != null ? sessionProtocol.uriText() : "unknown");
        }

        if (name != null) {
            buf.append(", name=").append(name);
        }

        if (isAvailable(flags, RequestLogProperty.REQUEST_HEADERS)) {
            buf.append(", headers=").append(sanitizedHeaders);
        }

        if (sanitizedContent != null) {
            buf.append(", content=").append(sanitizedContent);
        } else if (isAvailable(flags, RequestLogProperty.REQUEST_CONTENT_PREVIEW) &&
                   requestContentPreview != null) {
            buf.append(", contentPreview=").append(requestContentPreview);
        }

        if (sanitizedTrailers != null) {
            buf.append(", trailers=").append(sanitizedTrailers);
        }
        buf.append('}');

        requestStr = buf.toString();
        requestStrFlags = flags;

        return requestStr;
    }

    @Override
    public String toStringResponseOnly(Function<? super ResponseHeaders, ?> headersSanitizer,
                                       Function<Object, ?> contentSanitizer,
                                       Function<? super HttpHeaders, ?> trailersSanitizer) {

        requireNonNull(headersSanitizer, "headersSanitizer");
        requireNonNull(contentSanitizer, "contentSanitizer");
        requireNonNull(trailersSanitizer, "trailersSanitizer");

        // Only interested in the bits related with response.
        final int flags = this.flags & RequestLogProperty.FLAGS_RESPONSE_COMPLETE;
        if (responseStrFlags == flags) {
            assert responseStr != null;
            return responseStr;
        }

        if (!isAvailable(flags, RequestLogProperty.RESPONSE_START_TIME)) {
            responseStr = "{}";
            responseStrFlags = flags;
            return responseStr;
        }

        final String responseCauseString;
        if (isAvailable(flags, RequestLogProperty.RESPONSE_CAUSE) && responseCause != null) {
            responseCauseString = String.valueOf(responseCause);
        } else {
            responseCauseString = null;
        }

        final String sanitizedHeaders;
        if (isAvailable(flags, RequestLogProperty.RESPONSE_HEADERS)) {
            sanitizedHeaders = sanitize(headersSanitizer, responseHeaders);
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (isAvailable(flags, RequestLogProperty.RESPONSE_CONTENT) && responseContent != null) {
            sanitizedContent = sanitize(contentSanitizer, responseContent);
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (!responseTrailers.isEmpty()) {
            sanitizedTrailers = sanitize(trailersSanitizer, responseTrailers);
        } else {
            sanitizedTrailers = null;
        }

        final StringBuilder buf = TemporaryThreadLocals.get().stringBuilder();
        buf.append("{startTime=");
        TextFormatter.appendEpochMicros(buf, responseStartTimeMicros());

        if (isAvailable(flags, RequestLogProperty.RESPONSE_LENGTH)) {
            buf.append(", length=");
            TextFormatter.appendSize(buf, responseLength);
        }

        if (isAvailable(flags, RequestLogProperty.RESPONSE_END_TIME)) {
            buf.append(", duration=");
            TextFormatter.appendElapsed(buf, responseDurationNanos());
            buf.append(", totalDuration=");
            TextFormatter.appendElapsed(buf, totalDurationNanos());
        }

        if (responseCauseString != null) {
            buf.append(", cause=").append(responseCauseString);
        }

        if (sanitizedHeaders != null) {
            buf.append(", headers=").append(sanitizedHeaders);
        }

        if (sanitizedContent != null) {
            buf.append(", content=").append(sanitizedContent);
        } else if (isAvailable(flags, RequestLogProperty.RESPONSE_CONTENT_PREVIEW) &&
                   responseContentPreview != null) {
            buf.append(", contentPreview=").append(responseContentPreview);
        }

        if (sanitizedTrailers != null) {
            buf.append(", trailers=").append(sanitizedTrailers);
        }
        buf.append('}');

        final int numChildren = children != null ? children.size() : 0;
        if (numChildren > 1) {
            // Append only when there were retries which the numChildren is greater than 1.
            buf.append(", {totalAttempts=");
            buf.append(numChildren);
            buf.append('}');
        }

        responseStr = buf.toString();
        responseStrFlags = flags;

        return responseStr;
    }

    private static <T> String sanitize(Function<? super T, ?> headersSanitizer, T requestHeaders) {
        final Object sanitized = headersSanitizer.apply(requestHeaders);
        return sanitized != null ? sanitized.toString() : "<sanitized>";
    }

    private static final class RequestLogFuture extends EventLoopCheckingFuture<RequestLog> {

        final int interestedFlags;

        RequestLogFuture(int interestedFlags) {
            this.interestedFlags = interestedFlags;
        }

        void completeLog(RequestLog log) {
            super.complete(log);
        }

        @Override
        public boolean complete(RequestLog value) {
            // Disallow users from completing arbitrarily.
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            // Disallow users from completing arbitrarily.
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public void obtrudeValue(RequestLog value) {
            // Disallow users from completing arbitrarily.
            throw new UnsupportedOperationException();
        }

        @Override
        public void obtrudeException(Throwable ex) {
            // Disallow users from completing arbitrarily.
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A {@link RequestLog} that delegates to {@link DefaultRequestLog} to read properties without
     * checking availability.
     */
    private final class CompleteRequestLog implements RequestLog {

        @Override
        public boolean isComplete() {
            return true;
        }

        @Override
        public boolean isRequestComplete() {
            return true;
        }

        @Override
        public boolean isAvailable(RequestLogProperty property) {
            return true;
        }

        @Override
        public RequestLog partial() {
            return this;
        }

        @Override
        public CompletableFuture<RequestLog> whenComplete() {
            if (completedFuture == null) {
                completedFuture = UnmodifiableFuture.completedFuture(this);
            }
            return completedFuture;
        }

        @Override
        public CompletableFuture<RequestOnlyLog> whenRequestComplete() {
            // OK to cast because the future is unmodifiable.
            @SuppressWarnings("unchecked")
            final CompletableFuture<RequestOnlyLog> cast =
                    (CompletableFuture<RequestOnlyLog>) (CompletableFuture<?>) whenComplete();
            return cast;
        }

        @Override
        public CompletableFuture<RequestLog> whenAvailable(RequestLogProperty property) {
            return whenComplete();
        }

        @Override
        public CompletableFuture<RequestLog> whenAvailable(RequestLogProperty... properties) {
            return whenComplete();
        }

        @Override
        public CompletableFuture<RequestLog> whenAvailable(Iterable<RequestLogProperty> properties) {
            return whenComplete();
        }

        @Override
        public RequestLog ensureComplete() {
            return this;
        }

        @Override
        public RequestOnlyLog ensureRequestComplete() {
            return this;
        }

        @Override
        public RequestLog ensureAvailable(RequestLogProperty property) {
            return this;
        }

        @Override
        public RequestLog ensureAvailable(RequestLogProperty... properties) {
            return this;
        }

        @Override
        public RequestLog ensureAvailable(Iterable<RequestLogProperty> properties) {
            return this;
        }

        @Override
        public int availabilityStamp() {
            return RequestLogProperty.FLAGS_ALL_COMPLETE;
        }

        @Override
        public RequestContext context() {
            return ctx;
        }

        @Override
        public List<RequestLogAccess> children() {
            return DefaultRequestLog.this.children();
        }

        @Override
        public long requestStartTimeMicros() {
            return requestStartTimeMicros;
        }

        @Override
        public long requestStartTimeMillis() {
            return TimeUnit.MICROSECONDS.toMillis(requestStartTimeMicros);
        }

        @Override
        public long requestStartTimeNanos() {
            return requestStartTimeNanos;
        }

        @Override
        public Long requestFirstBytesTransferredTimeNanos() {
            return requestFirstBytesTransferredTimeNanosSet ? requestFirstBytesTransferredTimeNanos : null;
        }

        @Override
        public long requestEndTimeNanos() {
            return requestEndTimeNanos;
        }

        @Override
        public long requestLength() {
            return requestLength;
        }

        @Nullable
        @Override
        public Throwable requestCause() {
            return requestCause;
        }

        @Nullable
        @Override
        public Channel channel() {
            return channel;
        }

        @Nullable
        @Override
        public SSLSession sslSession() {
            return sslSession;
        }

        @Override
        public SessionProtocol sessionProtocol() {
            assert sessionProtocol != null;
            return sessionProtocol;
        }

        @Nullable
        @Override
        public ClientConnectionTimings connectionTimings() {
            return connectionTimings;
        }

        @Override
        public Scheme scheme() {
            assert scheme != null;
            return scheme;
        }

        @Nullable
        @Override
        public String name() {
            return name;
        }

        @Override
        public RequestHeaders requestHeaders() {
            return requestHeaders;
        }

        @Nullable
        @Override
        public Object requestContent() {
            return requestContent;
        }

        @Nullable
        @Override
        public Object rawRequestContent() {
            return rawRequestContent;
        }

        @Nullable
        @Override
        public String requestContentPreview() {
            return requestContentPreview;
        }

        @Override
        public HttpHeaders requestTrailers() {
            return requestTrailers;
        }

        @Override
        public String toStringRequestOnly(Function<? super RequestHeaders, ?> headersSanitizer,
                                          Function<Object, ?> contentSanitizer,
                                          Function<? super HttpHeaders, ?> trailersSanitizer) {
            return DefaultRequestLog.this.toStringRequestOnly(
                    headersSanitizer, contentSanitizer, trailersSanitizer);
        }

        @Override
        public long responseStartTimeMicros() {
            return responseStartTimeMicros;
        }

        @Override
        public long responseStartTimeMillis() {
            return TimeUnit.MICROSECONDS.toMillis(responseStartTimeMicros);
        }

        @Override
        public long responseStartTimeNanos() {
            return responseStartTimeNanos;
        }

        @Override
        public Long responseFirstBytesTransferredTimeNanos() {
            return responseFirstBytesTransferredTimeNanosSet ? responseFirstBytesTransferredTimeNanos : null;
        }

        @Override
        public long responseEndTimeNanos() {
            return responseEndTimeNanos;
        }

        @Override
        public long responseLength() {
            return responseLength;
        }

        @Nullable
        @Override
        public Throwable responseCause() {
            return responseCause;
        }

        @Override
        public ResponseHeaders responseHeaders() {
            return responseHeaders;
        }

        @Nullable
        @Override
        public Object responseContent() {
            return responseContent;
        }

        @Nullable
        @Override
        public Object rawResponseContent() {
            return rawResponseContent;
        }

        @Nullable
        @Override
        public String responseContentPreview() {
            return responseContentPreview;
        }

        @Override
        public HttpHeaders responseTrailers() {
            return responseTrailers;
        }

        @Override
        public String toStringResponseOnly() {
            return DefaultRequestLog.this.toStringResponseOnly();
        }

        @Override
        public String toStringResponseOnly(Function<? super HttpHeaders, ?> headersSanitizer,
                                           Function<Object, ?> contentSanitizer) {
            return DefaultRequestLog.this.toStringResponseOnly(headersSanitizer, contentSanitizer);
        }

        @Override
        public String toStringResponseOnly(Function<? super ResponseHeaders, ?> headersSanitizer,
                                           Function<Object, ?> contentSanitizer,
                                           Function<? super HttpHeaders, ?> trailersSanitizer) {
            return DefaultRequestLog.this.toStringResponseOnly(headersSanitizer, contentSanitizer,
                                                               trailersSanitizer);
        }
    }
}
