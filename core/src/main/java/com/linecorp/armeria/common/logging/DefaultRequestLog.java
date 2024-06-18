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
import static com.linecorp.armeria.common.logging.RequestLogProperty.FLAGS_ALL_COMPLETE;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.Lock;

import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceNaming;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;

/**
 * Default {@link RequestLog} implementation.
 */
final class DefaultRequestLog implements RequestLog, RequestLogBuilder {

    private static final AtomicIntegerFieldUpdater<DefaultRequestLog> flagsUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRequestLog.class, "flags");

    private static final AtomicIntegerFieldUpdater<DefaultRequestLog> deferredFlagsUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRequestLog.class, "deferredFlags");

    private static final RequestHeaders DUMMY_REQUEST_HEADERS_HTTP =
            RequestHeaders.builder(HttpMethod.UNKNOWN, "?").scheme("http").authority("?").build();
    private static final RequestHeaders DUMMY_REQUEST_HEADERS_HTTPS =
            RequestHeaders.builder(HttpMethod.UNKNOWN, "?").scheme("https").authority("?").build();
    private static final ResponseHeaders DUMMY_RESPONSE_HEADERS = ResponseHeaders.of(HttpStatus.UNKNOWN);

    private final RequestContext ctx;
    private int currentAttempt;

    private final CompleteRequestLog notCheckingAccessor = new CompleteRequestLog();

    @Nullable
    private RequestLogAccess parent;
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

    @GuardedBy("lock")
    private final List<RequestLogFuture> pendingFutures = new ArrayList<>(4);

    private final Lock lock = new ReentrantShortLock();
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
    private String serviceName;
    @Nullable
    private String name;
    @Nullable
    private String fullName;
    @Nullable
    private String authenticatedUser;

    @Nullable
    private RequestHeaders requestHeaders;
    private HttpHeaders requestTrailers = HttpHeaders.of();

    @Nullable
    private ResponseHeaders responseHeaders;
    private HttpHeaders responseTrailers = HttpHeaders.of();

    @Nullable
    private Object requestContent;
    @Nullable
    private Object rawRequestContent;
    @Nullable
    private Object responseContent;
    @Nullable
    private Object rawResponseContent;

    DefaultRequestLog(RequestContext ctx) {
        this.ctx = requireNonNull(ctx, "ctx");
    }

    // Methods from RequestLogAccess

    @Override
    public boolean isComplete() {
        return isComplete(flags);
    }

    private static boolean isComplete(int flags) {
        return flags == FLAGS_ALL_COMPLETE;
    }

    @Override
    public boolean isRequestComplete() {
        return hasInterestedFlags(flags, RequestLogProperty.FLAGS_REQUEST_COMPLETE);
    }

    @Override
    public boolean isAvailable(RequestLogProperty property) {
        requireNonNull(property, "property");
        return hasInterestedFlags(flags, property.flag());
    }

    @Override
    public boolean isAvailable(RequestLogProperty... properties) {
        requireNonNull(properties, "properties");
        checkArgument(properties.length != 0, "properties is empty.");
        return isAvailable(interestedFlags(properties));
    }

    @Override
    public boolean isAvailable(Iterable<RequestLogProperty> properties) {
        requireNonNull(properties, "properties");
        final int flags = interestedFlags(properties);
        checkArgument(flags != 0, "properties is empty.");
        return isAvailable(flags);
    }

    private boolean isAvailable(int interestedFlags) {
        return hasInterestedFlags(flags, interestedFlags);
    }

    @Nullable
    @Override
    public RequestLog getIfAvailable(RequestLogProperty... properties) {
        return isAvailable(properties) ? this : null;
    }

    @Nullable
    @Override
    public RequestLog getIfAvailable(Iterable<RequestLogProperty> properties) {
        return isAvailable(properties) ? this : null;
    }

    private static boolean hasInterestedFlags(int flags, RequestLogProperty property) {
        return hasInterestedFlags(flags, property.flag());
    }

    private static boolean hasInterestedFlags(int flags, int interestedFlags) {
        return (flags & interestedFlags) == interestedFlags;
    }

    private static int interestedFlags(RequestLogProperty... properties) {
        int flags = 0;
        for (RequestLogProperty p : properties) {
            requireNonNull(p, "properties contains null.");
            flags |= p.flag();
        }
        return flags;
    }

    private static int interestedFlags(Iterable<RequestLogProperty> properties) {
        int flags = 0;
        for (RequestLogProperty p : properties) {
            requireNonNull(p, "properties contains null.");
            flags |= p.flag();
        }
        return flags;
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
        return future(FLAGS_ALL_COMPLETE);
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

    @Nullable
    @Override
    public RequestLogAccess parent() {
        return parent;
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

        if (hasInterestedFlags(flags, interestedFlags)) {
            future = completedFuture(flags);
        } else {
            final RequestLogFuture[] satisfiedFutures;
            final RequestLogFuture newFuture = new RequestLogFuture(interestedFlags);
            lock.lock();
            try {
                pendingFutures.add(newFuture);
                satisfiedFutures = removeSatisfiedFutures(pendingFutures);
            } finally {
                lock.unlock();
            }
            if (satisfiedFutures != null) {
                completeSatisfiedFutures(satisfiedFutures, partial(flags), ctx);
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
                lock.lock();
                try {
                    satisfiedFutures = removeSatisfiedFutures(pendingFutures);
                } finally {
                    lock.unlock();
                }
                if (satisfiedFutures != null) {
                    final RequestLog log = partial(newFlags);
                    completeSatisfiedFutures(satisfiedFutures, log, ctx);
                }
                break;
            }
        }
    }

    private static void completeSatisfiedFutures(RequestLogFuture[] satisfiedFutures, RequestLog log,
                                                 RequestContext ctx) {
        if (!ctx.eventLoop().inEventLoop()) {
            ctx.eventLoop().execute(() -> completeSatisfiedFutures(satisfiedFutures, log, ctx));
            return;
        }
        for (RequestLogFuture f : satisfiedFutures) {
            if (f == null) {
                break;
            }
            f.completeLog(log);
        }
    }

    @Nullable
    private RequestLogFuture[] removeSatisfiedFutures(List<RequestLogFuture> pendingFutures) {
        if (pendingFutures.isEmpty()) {
            return null;
        }

        final int maxNumListeners = pendingFutures.size();
        final Iterator<RequestLogFuture> i = pendingFutures.iterator();
        RequestLogFuture[] satisfied = null;
        int numSatisfied = 0;

        do {
            final RequestLogFuture e = i.next();
            final int interestedFlags = e.interestedFlags;
            // 'flags' should be read inside 'lock' when completing 'pendingFutures' to ensure visibility.
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

    // Methods related with deferred properties

    @Override
    public boolean isDeferred(RequestLogProperty property) {
        requireNonNull(property, "property");
        final int flag = property.flag();
        return isDeferred(flag);
    }

    @Override
    public boolean isDeferred(RequestLogProperty... properties) {
        requireNonNull(properties, "properties");
        checkArgument(properties.length != 0, "properties is empty.");
        return isDeferred(interestedFlags(properties));
    }

    @Override
    public boolean isDeferred(Iterable<RequestLogProperty> properties) {
        requireNonNull(properties, "properties");
        final int flags = interestedFlags(properties);
        checkArgument(flags != 0, "properties is empty.");
        return isDeferred(flags);
    }

    private boolean isDeferred(int flag) {
        return (deferredFlags & flag) == flag;
    }

    @Override
    public void defer(RequestLogProperty property) {
        requireNonNull(property, "property");
        defer(property.flag());
    }

    @Override
    public void defer(RequestLogProperty... properties) {
        requireNonNull(properties, "properties");
        defer(interestedFlags(properties));
    }

    @Override
    public void defer(Iterable<RequestLogProperty> properties) {
        requireNonNull(properties, "properties");
        defer(interestedFlags(properties));
    }

    private void defer(int flag) {
        if (hasInterestedFlags(flag, RequestLogProperty.REQUEST_CONTENT.flag())) {
            flag |= RequestLogProperty.NAME.flag();
        }

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

        if (child instanceof DefaultRequestLog) {
            checkState(((DefaultRequestLog) child).parent == null, "child has parent already");
            ((DefaultRequestLog) child).parent = this;
        }

        if (children == null) {
            // first child's all request-side logging events are propagated immediately to the parent
            children = new ArrayList<>();
            propagateRequestSideLog(child);
        }

        children.add(child);
        if (child instanceof DefaultRequestLog) {
            ((DefaultRequestLog) child).currentAttempt = children.size();
        }
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
                 final String serviceName = log.serviceName();
                 final String name = log.name();
                 if (serviceName != null) {
                     name(serviceName, name);
                 } else {
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
            // Note that we do not propagate `requestCause` because otherwise the request which succeeded after
            // retries can be considered to have failed.
            endRequest0(/* requestCause */ null, log.requestEndTimeNanos());
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
        if (lastChild.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
            // Update responseCause first if available because callbacks of the other properties may need it
            // to retry or open circuit breakers.
            final Throwable responseCause = lastChild.responseCause();
            if (responseCause != null) {
                responseCause(responseCause);
            }
        }

        // Update the available properties without adding a callback if the lastChild already has them.
        if (lastChild.isAvailable(RequestLogProperty.RESPONSE_START_TIME)) {
            startResponse(lastChild.responseStartTimeNanos(), lastChild.responseStartTimeMicros(), true);
        } else {
            lastChild.whenAvailable(RequestLogProperty.RESPONSE_START_TIME)
                     .thenAccept(log -> startResponse(log.responseStartTimeNanos(),
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

        if (lastChild.isAvailable(RequestLogProperty.RESPONSE_TRAILERS)) {
            responseTrailers(lastChild.responseTrailers());
        } else {
            lastChild.whenAvailable(RequestLogProperty.RESPONSE_TRAILERS)
                     .thenAccept(log -> responseTrailers(log.responseTrailers()));
        }

        if (lastChild.isComplete()) {
            propagateResponseEndData(lastChild);
        } else {
            lastChild.whenComplete().thenAccept(this::propagateResponseEndData);
        }
    }

    private void propagateResponseEndData(RequestLog log) {
        responseContent(log.responseContent(), log.rawResponseContent());
        responseLength(log.responseLength());
        responseContentPreview(log.responseContentPreview());
        responseTrailers(log.responseTrailers());
        endResponse0(log.responseCause(), log.responseEndTimeNanos());
    }

    // Request-side methods.

    @Override
    public void startRequest(long requestStartTimeNanos, long requestStartTimeMicros) {
        if (isAvailable(RequestLogProperty.REQUEST_START_TIME)) {
            return;
        }
        this.requestStartTimeNanos = requestStartTimeNanos;
        this.requestStartTimeMicros = requestStartTimeMicros;

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
        maybeSetScheme();
        updateFlags(RequestLogProperty.SESSION);
    }

    private void maybeSetScheme() {
        if (isAvailable(RequestLogProperty.SCHEME) ||
            serializationFormat == SerializationFormat.NONE) {
            return;
        }

        assert sessionProtocol != null;
        scheme = Scheme.of(serializationFormat, sessionProtocol);
        updateFlags(RequestLogProperty.SCHEME);
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
    public SerializationFormat serializationFormat() {
        return serializationFormat;
    }

    @Override
    public Scheme scheme() {
        ensureAvailable(RequestLogProperty.SCHEME);
        assert scheme != null;
        return scheme;
    }

    @Nullable
    @Override
    public String serviceName() {
        ensureAvailable(RequestLogProperty.NAME);
        return serviceName;
    }

    @Override
    public String name() {
        ensureAvailable(RequestLogProperty.NAME);
        assert name != null;
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
    public void name(String serviceName, String name) {
        requireNonNull(serviceName, "serviceName");
        checkArgument(!serviceName.isEmpty(), "serviceName is empty.");
        requireNonNull(name, "name");
        checkArgument(!name.isEmpty(), "name is empty.");

        if (isAvailable(RequestLogProperty.NAME)) {
            return;
        }

        this.serviceName = serviceName;
        this.name = name;
        updateFlags(RequestLogProperty.NAME);
    }

    @Override
    public String fullName() {
        ensureAvailable(RequestLogProperty.NAME);
        if (fullName != null) {
            return fullName;
        }

        assert name != null;

        if (serviceName != null) {
            return fullName = serviceName + '/' + name;
        } else {
            return fullName = name;
        }
    }

    @Override
    public String authenticatedUser() {
        ensureAvailable(RequestLogProperty.AUTHENTICATED_USER);
        return authenticatedUser;
    }

    @Override
    public void authenticatedUser(String authenticatedUser) {
        if (isAvailable(RequestLogProperty.AUTHENTICATED_USER)) {
            return;
        }
        this.authenticatedUser = requireNonNull(authenticatedUser, "authenticatedUser");
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
        assert requestHeaders != null;
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
        if (requestContent instanceof RpcRequest && ctx.rpcRequest() == null) {
            ctx.updateRpcRequest((RpcRequest) requestContent);
        }
        updateFlags(RequestLogProperty.REQUEST_CONTENT);

        final int requestCompletionFlags = RequestLogProperty.FLAGS_REQUEST_COMPLETE & ~deferredFlags;
        if (isAvailable(requestCompletionFlags)) {
            setNamesIfAbsent();
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
            return;
        }

        this.requestContentPreview = requestContentPreview;
        updateFlags(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
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
    public int currentAttempt() {
        return currentAttempt;
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
        final int deferredFlags;
        if (requestCause != null) {
            // Will auto-fill request content and its preview if request has failed.
            deferredFlags = this.deferredFlags & (~(RequestLogProperty.REQUEST_CONTENT.flag() |
                                                    RequestLogProperty.REQUEST_CONTENT_PREVIEW.flag()));
        } else {
            deferredFlags = this.deferredFlags;
        }

        final int flags = RequestLogProperty.FLAGS_REQUEST_COMPLETE & ~deferredFlags;
        if (isAvailable(flags)) {
            return;
        }

        // if the request is not started yet, call startRequest() with requestEndTimeNanos so that
        // totalRequestDuration will be 0

        startRequest(requestEndTimeNanos, SystemInfo.currentTimeMicros());
        session(null, context().sessionProtocol(), null, null);
        assert sessionProtocol != null;

        if (scheme == null) {
            scheme = Scheme.of(serializationFormat, sessionProtocol);
        }

        if (requestHeaders == null) {
            final HttpRequest req = context().request();
            if (req != null) {
                requestHeaders = req.headers();
            } else {
                requestHeaders = sessionProtocol.isTls() ? DUMMY_REQUEST_HEADERS_HTTPS
                                                         : DUMMY_REQUEST_HEADERS_HTTP;
            }
        }

        // Set names if request content is not deferred or it was deferred but has been set
        // before the request completion.
        if (!hasInterestedFlags(deferredFlags, RequestLogProperty.REQUEST_CONTENT) ||
            isAvailable(RequestLogProperty.REQUEST_CONTENT)) {
            setNamesIfAbsent();
        }
        this.requestEndTimeNanos = requestEndTimeNanos;

        if (requestCause instanceof HttpStatusException || requestCause instanceof HttpResponseException) {
            // Log the requestCause only when an Http{Status,Response}Exception was created with a cause.
            this.requestCause = requestCause.getCause();
        } else if (!(requestCause instanceof ResponseCompleteException)) {
            this.requestCause = requestCause;
        }
        updateFlags(flags);
    }

    private void setNamesIfAbsent() {
        if (name == null) {
            String newServiceName = null;
            String newName = null;
            ServiceConfig config = null;
            ServiceRequestContext sctx = null;

            // Set the default names from ServiceConfig
            if (ctx instanceof ServiceRequestContext) {
                sctx = (ServiceRequestContext) ctx;
                config = sctx.config();
                newServiceName = config.defaultServiceNaming().serviceName(sctx);
                newName = config.defaultLogName();
            }

            RpcRequest rpcReq = ctx.rpcRequest();
            if (rpcReq == null && requestContent instanceof RpcRequest) {
                rpcReq = (RpcRequest) requestContent;
            }

            // Set serviceName from ServiceType or innermost class name
            if (newServiceName == null) {
                if (config != null) {
                    newServiceName = ServiceNaming.fullTypeName().serviceName(sctx);
                } else if (rpcReq != null) {
                    newServiceName = rpcReq.serviceName();
                }
            }

            if (newName == null) {
                if (rpcReq != null) {
                    newName = rpcReq.method();
                } else {
                    newName = ctx.method().name();
                }
            }

            serviceName = newServiceName;
            name = newName;

            updateFlags(RequestLogProperty.NAME);
        }
    }

    // Response-side methods.

    @Override
    public void startResponse() {
        startResponse(System.nanoTime(), SystemInfo.currentTimeMicros(), true);
    }

    @Override
    public void startResponse(long responseStartTimeNanos, long responseStartTimeMicros) {
        startResponse(responseStartTimeNanos, responseStartTimeMicros, true);
    }

    private void startResponse(long responseStartTimeNanos, long responseStartTimeMicros, boolean updateFlags) {
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
    public void responseCause(Throwable cause) {
        if (isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
            return;
        }

        requireNonNull(cause, "cause");
        setResponseCause(cause, true);
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
        assert responseHeaders != null;
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
                setResponseCause(rpcResponse.cause(), true);
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
        final int deferredFlags;
        if (responseCause != null) {
            // Will auto-fill response content and its preview if response has failed.
            deferredFlags = this.deferredFlags & (~(RequestLogProperty.RESPONSE_CONTENT.flag() |
                                                    RequestLogProperty.RESPONSE_CONTENT_PREVIEW.flag()));
        } else {
            deferredFlags = this.deferredFlags;
        }

        final int flags = RequestLogProperty.FLAGS_RESPONSE_COMPLETE & ~deferredFlags;
        if (isAvailable(flags)) {
            return;
        }

        // if the response is not started yet, call startResponse() with responseEndTimeNanos so that
        // totalResponseDuration will be 0
        startResponse(responseEndTimeNanos, SystemInfo.currentTimeMicros(), false);

        this.responseEndTimeNanos = responseEndTimeNanos;
        if (responseHeaders == null) {
            if (responseCause instanceof HttpStatusException) {
                responseHeaders = ResponseHeaders.of(((HttpStatusException) responseCause).httpStatus());
            } else {
                responseHeaders = DUMMY_RESPONSE_HEADERS;
            }
        }
        setResponseCause(responseCause, false);
        updateFlags(flags);
    }

    private void setResponseCause(@Nullable Throwable responseCause, boolean updateFlag) {
        if (this.responseCause != null) {
            return;
        }
        if (responseCause instanceof HttpStatusException ||
            responseCause instanceof HttpResponseException) {
            // Log the responseCause only when an Http{Status,Response}Exception was created with a cause.
            responseCause = responseCause.getCause();
        }
        if (responseCause != null) {
            this.responseCause = responseCause;
            if (updateFlag) {
                updateFlags(RequestLogProperty.RESPONSE_CAUSE);
            }
        }
    }

    @Override
    public String toString() {
        final String req = toStringRequestOnly();
        final String res = toStringResponseOnly();
        final int numChildren = children != null ? children.size() : 0;

        if (numChildren == 0) {
            try (TemporaryThreadLocals ttl = TemporaryThreadLocals.acquire()) {
                return toStringWithoutChildren(ttl.stringBuilder(), req, res).toString();
            }
        } else {
            return toStringWithChildren(req, res, numChildren);
        }
    }

    private String toStringWithChildren(String req, String res, int numChildren) {
        assert children != null;

        final StringBuilder buf = toStringWithoutChildren(new StringBuilder(1024), req, res);
        buf.append(System.lineSeparator())
           .append("Children:");

        for (int i = 0; i < numChildren; i++) {
            buf.append(System.lineSeparator());
            buf.append('\t');
            buf.append(children.get(i));
        }
        return buf.toString();
    }

    private static StringBuilder toStringWithoutChildren(StringBuilder buf, String req, String res) {
        return buf.append('{')
                  .append(req)
                  .append(", ")
                  .append(res)
                  .append('}');
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
            requireNonNull(property, "property");
            return true;
        }

        @Override
        public boolean isAvailable(RequestLogProperty... properties) {
            requireNonNull(properties, "properties");
            checkArgument(properties.length != 0, "properties is empty.");
            return true;
        }

        @Override
        public boolean isAvailable(Iterable<RequestLogProperty> properties) {
            requireNonNull(properties, "properties");
            checkArgument(!Iterables.isEmpty(properties), "properties is empty.");
            return true;
        }

        @Nullable
        @Override
        public RequestLog getIfAvailable(RequestLogProperty... properties) {
            requireNonNull(properties, "properties");
            checkArgument(properties.length != 0, "properties is empty.");
            return this;
        }

        @Nullable
        @Override
        public RequestLog getIfAvailable(Iterable<RequestLogProperty> properties) {
            requireNonNull(properties, "properties");
            checkArgument(!Iterables.isEmpty(properties), "properties is empty.");
            return this;
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
            return FLAGS_ALL_COMPLETE;
        }

        @Override
        public RequestContext context() {
            return ctx;
        }

        @Nullable
        @Override
        public RequestLogAccess parent() {
            return DefaultRequestLog.this.parent();
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
        public SerializationFormat serializationFormat() {
            return serializationFormat;
        }

        @Override
        public Scheme scheme() {
            assert scheme != null;
            return scheme;
        }

        @Nullable
        @Override
        public String serviceName() {
            return serviceName;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String fullName() {
            return DefaultRequestLog.this.fullName();
        }

        @Override
        public String authenticatedUser() {
            return authenticatedUser;
        }

        @Override
        public RequestHeaders requestHeaders() {
            assert requestHeaders != null;
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
        public int currentAttempt() {
            return currentAttempt;
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
            assert responseHeaders != null;
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
        public String toString() {
            return DefaultRequestLog.this.toString();
        }
    }
}
