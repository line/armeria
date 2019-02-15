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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.COMPLETE;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_END;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_FIRST_BYTES_TRANSFERRED;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_END;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_FIRST_BYTES_TRANSFERRED;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.SCHEME;
import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.TextFormatter;

import io.netty.channel.Channel;
import io.netty.util.internal.PlatformDependent;

/**
 * Default {@link RequestLog} implementation.
 */
public class DefaultRequestLog implements RequestLog, RequestLogBuilder {

    private static final AtomicIntegerFieldUpdater<DefaultRequestLog> flagsUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRequestLog.class, "flags");

    private static final int FLAGS_REQUEST_END_WITHOUT_CONTENT =
            REQUEST_END.setterFlags() & ~REQUEST_CONTENT.setterFlags();
    private static final int FLAGS_RESPONSE_END_WITHOUT_CONTENT =
            RESPONSE_END.setterFlags() & ~RESPONSE_CONTENT.setterFlags();

    private static final int STRING_BUILDER_CAPACITY = 512;

    private static final HttpHeaders DUMMY_REQUEST_HEADERS_HTTP =
            HttpHeaders.of().scheme("http").authority("?").method(HttpMethod.UNKNOWN).path("?").asImmutable();
    private static final HttpHeaders DUMMY_REQUEST_HEADERS_HTTPS =
            HttpHeaders.of().scheme("https").authority("?").method(HttpMethod.UNKNOWN).path("?").asImmutable();
    private static final HttpHeaders DUMMY_RESPONSE_HEADERS = HttpHeaders.of(HttpStatus.UNKNOWN).asImmutable();

    private final RequestContext ctx;

    @Nullable
    private List<RequestLog> children;
    private boolean hasLastChild;

    /**
     * Updated by {@link #flagsUpdater}.
     */
    @SuppressWarnings("unused")
    private volatile int flags;
    private final List<ListenerEntry> listeners = new ArrayList<>(4);
    private volatile boolean requestContentDeferred;
    private volatile boolean responseContentDeferred;

    private long requestStartTimeMicros;
    private long requestStartTimeNanos;
    private long requestFirstBytesTransferredTimeNanos;
    private long requestEndTimeNanos;
    private long requestLength;
    @Nullable
    private Throwable requestCause;

    private long responseStartTimeMicros;
    private long responseStartTimeNanos;
    private long responseFirstBytesTransferredTimeNanos;
    private long responseEndTimeNanos;
    private long responseLength;
    @Nullable
    private Throwable responseCause;

    @Nullable
    private Channel channel;
    @Nullable
    private SSLSession sslSession;
    @Nullable
    private SessionProtocol sessionProtocol;
    private SerializationFormat serializationFormat = SerializationFormat.NONE;

    private HttpHeaders requestHeaders = DUMMY_REQUEST_HEADERS_HTTP;
    private HttpHeaders responseHeaders = DUMMY_RESPONSE_HEADERS;
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

    @Override
    public void addChild(RequestLog child) {
        checkState(!hasLastChild, "last child is already added");
        requireNonNull(child, "child");
        if (children == null) {
            // first child's all request-side logging events are propagated immediately to the parent
            children = new ArrayList<>();
            propagateRequestSideLog(child);
        }
        children.add(child);
    }

    private void propagateRequestSideLog(RequestLog child) {
        child.addListener(log -> startRequest0(log.channel(), log.sessionProtocol(), null,
                                               log.requestStartTimeNanos(), log.requestStartTimeMicros(), true),
                          REQUEST_START);
        child.addListener(log -> serializationFormat(log.serializationFormat()), SCHEME);
        child.addListener(log -> requestFirstBytesTransferred(log.requestFirstBytesTransferredTimeNanos()),
                          REQUEST_FIRST_BYTES_TRANSFERRED);
        child.addListener(log -> requestHeaders(log.requestHeaders()), REQUEST_HEADERS);
        child.addListener(log -> requestContent(log.requestContent(), log.rawRequestContent()),
                          REQUEST_CONTENT);
        child.addListener(log -> {
            requestLength(log.requestLength());
            endRequest0(log.requestCause(), log.requestEndTimeNanos());
        }, REQUEST_END);
    }

    @Override
    public void endResponseWithLastChild() {
        checkState(!hasLastChild, "last child is already added");
        checkState(children != null && !children.isEmpty(), "at least one child should be already added");
        hasLastChild = true;
        final RequestLog lastChild = children.get(children.size() - 1);
        propagateResponseSideLog(lastChild);
    }

    private void propagateResponseSideLog(RequestLog lastChild) {
        // update the available logs if the lastChild already has them
        if (lastChild.isAvailable(RESPONSE_START)) {
            startResponse0(lastChild.responseStartTimeNanos(), lastChild.responseStartTimeMicros(), true);
        }

        if (lastChild.isAvailable(RESPONSE_FIRST_BYTES_TRANSFERRED)) {
            responseFirstBytesTransferred(lastChild.responseFirstBytesTransferredTimeNanos());
        }

        if (lastChild.isAvailable(RESPONSE_HEADERS)) {
            responseHeaders(lastChild.responseHeaders());
        }

        if (lastChild.isAvailable(RESPONSE_CONTENT)) {
            responseContent(lastChild.responseContent(), lastChild.rawResponseContent());
        }

        if (lastChild.isAvailable(RESPONSE_END)) {
            responseLength(lastChild.responseLength());
            endResponse0(lastChild.responseCause(), lastChild.responseEndTimeNanos());
        }

        lastChild.addListener(log -> startResponse0(
                log.responseStartTimeNanos(), log.responseStartTimeMicros(), true), RESPONSE_START);
        lastChild.addListener(log -> responseFirstBytesTransferred(
                log.responseFirstBytesTransferredTimeNanos()), RESPONSE_FIRST_BYTES_TRANSFERRED);
        lastChild.addListener(log -> responseHeaders(log.responseHeaders()), RESPONSE_HEADERS);
        lastChild.addListener(log -> responseContent(
                log.responseContent(), log.rawResponseContent()), RESPONSE_CONTENT);
        lastChild.addListener(log -> {
            responseLength(lastChild.responseLength());
            endResponse0(log.responseCause(), log.responseEndTimeNanos());
        }, RESPONSE_END);
    }

    @Override
    public List<RequestLog> children() {
        return children != null ? ImmutableList.copyOf(children) : ImmutableList.of();
    }

    @Override
    public Set<RequestLogAvailability> availabilities() {
        return RequestLogAvailabilitySet.of(flags);
    }

    @Override
    public boolean isAvailable(RequestLogAvailability availability) {
        return isAvailable(availability.getterFlags());
    }

    @Override
    public boolean isAvailable(RequestLogAvailability... availabilities) {
        return isAvailable(getterFlags(availabilities));
    }

    @Override
    public boolean isAvailable(Iterable<RequestLogAvailability> availabilities) {
        return isAvailable(getterFlags(availabilities));
    }

    private boolean isAvailable(int interestedFlags) {
        return (flags & interestedFlags) == interestedFlags;
    }

    private static boolean isAvailable(int flags, RequestLogAvailability availability) {
        final int interestedFlags = availability.getterFlags();
        return (flags & interestedFlags) == interestedFlags;
    }

    private boolean isAvailabilityAlreadyUpdated(RequestLogAvailability availability) {
        return isAvailable(availability.setterFlags());
    }

    @Override
    public void addListener(RequestLogListener listener, RequestLogAvailability availability) {
        requireNonNull(listener, "listener");
        requireNonNull(availability, "availability");
        addListener(listener, availability.getterFlags());
    }

    @Override
    public void addListener(RequestLogListener listener, RequestLogAvailability... availabilities) {
        requireNonNull(listener, "listener");
        requireNonNull(availabilities, "availabilities");
        addListener(listener, getterFlags(availabilities));
    }

    @Override
    public void addListener(RequestLogListener listener, Iterable<RequestLogAvailability> availabilities) {
        requireNonNull(listener, "listener");
        requireNonNull(availabilities, "availabilities");
        addListener(listener, getterFlags(availabilities));
    }

    private void addListener(RequestLogListener listener, int interestedFlags) {
        if (interestedFlags == 0) {
            throw new IllegalArgumentException("no availability specified");
        }

        if (isAvailable(interestedFlags)) {
            // No need to add to 'listeners'.
            RequestLogListenerInvoker.invokeOnRequestLog(listener, this);
            return;
        }

        final ListenerEntry e = new ListenerEntry(listener, interestedFlags);
        final RequestLogListener[] satisfiedListeners;
        synchronized (listeners) {
            listeners.add(e);
            satisfiedListeners = removeSatisfiedListeners();
        }
        notifyListeners(satisfiedListeners);
    }

    private static int getterFlags(RequestLogAvailability[] availabilities) {
        int flags = 0;
        for (RequestLogAvailability a : availabilities) {
            flags |= a.getterFlags();
        }
        return flags;
    }

    private static int getterFlags(Iterable<RequestLogAvailability> availabilities) {
        int flags = 0;
        for (RequestLogAvailability a : availabilities) {
            flags |= a.getterFlags();
        }
        return flags;
    }

    @Override
    public RequestContext context() {
        return ctx;
    }

    @Override
    public void startRequest(Channel channel, SessionProtocol sessionProtocol,
                             @Nullable SSLSession sslSession) {
        requireNonNull(channel, "channel");
        requireNonNull(sessionProtocol, "sessionProtocol");
        startRequest0(channel, sessionProtocol, sslSession, true);
    }

    @Override
    public void startRequest(Channel channel, SessionProtocol sessionProtocol, @Nullable SSLSession sslSession,
                             long requestStartTimeNanos, long requestStartTimeMicros) {
        requireNonNull(channel, "channel");
        requireNonNull(sessionProtocol, "sessionProtocol");
        startRequest0(channel, sessionProtocol, sslSession,
                      requestStartTimeNanos, requestStartTimeMicros, true);
    }

    private void startRequest0(Channel channel, SessionProtocol sessionProtocol,
                               @Nullable SSLSession sslSession, boolean updateAvailability) {
        startRequest0(channel, sessionProtocol, sslSession,
                      System.nanoTime(), currentTimeMicros(),
                      updateAvailability);
    }

    private void startRequest0(@Nullable Channel channel, SessionProtocol sessionProtocol,
                               @Nullable SSLSession sslSession, long requestStartTimeNanos,
                               long requestStartTimeMicros, boolean updateAvailability) {
        if (isAvailabilityAlreadyUpdated(REQUEST_START)) {
            return;
        }

        this.requestStartTimeNanos = requestStartTimeNanos;
        this.requestStartTimeMicros = requestStartTimeMicros;
        this.channel = channel;
        this.sslSession = sslSession;
        this.sessionProtocol = sessionProtocol;
        if (sessionProtocol.isTls()) {
            // Switch to the dummy headers with ':scheme=https' if the connection is TLS.
            if (requestHeaders == DUMMY_REQUEST_HEADERS_HTTP) {
                requestHeaders = DUMMY_REQUEST_HEADERS_HTTPS;
            }
        }

        if (updateAvailability) {
            updateAvailability(REQUEST_START);
        }
    }

    @Override
    public long requestStartTimeMicros() {
        ensureAvailability(REQUEST_START);
        return requestStartTimeMicros;
    }

    @Override
    public long requestStartTimeMillis() {
        return TimeUnit.MICROSECONDS.toMillis(requestStartTimeMicros());
    }

    @Override
    public long requestStartTimeNanos() {
        ensureAvailability(REQUEST_START);
        return requestStartTimeNanos;
    }

    @Override
    public long requestFirstBytesTransferredTimeNanos() {
        ensureAvailability(REQUEST_FIRST_BYTES_TRANSFERRED);
        return requestFirstBytesTransferredTimeNanos;
    }

    @Override
    public long requestEndTimeNanos() {
        ensureAvailability(REQUEST_END);
        return requestEndTimeNanos;
    }

    @Override
    public long requestDurationNanos() {
        ensureAvailability(REQUEST_END);
        return requestEndTimeNanos - requestStartTimeNanos;
    }

    @Override
    public Throwable requestCause() {
        ensureAvailability(REQUEST_END);
        return requestCause;
    }

    @Override
    public Channel channel() {
        ensureAvailability(REQUEST_START);
        return channel;
    }

    @Override
    public SSLSession sslSession() {
        ensureAvailability(REQUEST_START);
        return sslSession;
    }

    @Override
    public SessionProtocol sessionProtocol() {
        ensureAvailability(REQUEST_START);
        return sessionProtocol;
    }

    @Override
    public SerializationFormat serializationFormat() {
        ensureAvailability(SCHEME);
        return serializationFormat;
    }

    @Override
    public void serializationFormat(SerializationFormat serializationFormat) {
        if (isAvailabilityAlreadyUpdated(SCHEME)) {
            return;
        }

        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        updateAvailability(SCHEME);
    }

    @Override
    public Scheme scheme() {
        ensureAvailability(SCHEME);
        return Scheme.of(serializationFormat, sessionProtocol);
    }

    @Override
    public long requestLength() {
        ensureAvailability(REQUEST_END);
        return requestLength;
    }

    @Override
    public void requestLength(long requestLength) {
        if (requestLength < 0) {
            throw new IllegalArgumentException("requestLength: " + requestLength + " (expected: >= 0)");
        }

        if (isAvailable(REQUEST_END)) {
            return;
        }

        this.requestLength = requestLength;
    }

    @Override
    public void requestFirstBytesTransferred() {
        if (isAvailabilityAlreadyUpdated(REQUEST_FIRST_BYTES_TRANSFERRED)) {
            return;
        }
        requestFirstBytesTransferred0(System.nanoTime());
    }

    @Override
    public void requestFirstBytesTransferred(long requestFirstBytesTransferredTimeNanos) {
        if (isAvailabilityAlreadyUpdated(REQUEST_FIRST_BYTES_TRANSFERRED)) {
            return;
        }
        requestFirstBytesTransferred0(requestFirstBytesTransferredTimeNanos);
    }

    private void requestFirstBytesTransferred0(long requestFirstBytesTransferredTimeNanos) {
        this.requestFirstBytesTransferredTimeNanos = requestFirstBytesTransferredTimeNanos;
        updateAvailability(REQUEST_FIRST_BYTES_TRANSFERRED);
    }

    @Override
    public void increaseRequestLength(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("deltaBytes: " + deltaBytes + " (expected: >= 0)");
        }

        if (isAvailable(REQUEST_END)) {
            return;
        }

        requestLength += deltaBytes;
    }

    @Override
    public HttpHeaders requestHeaders() {
        ensureAvailability(REQUEST_HEADERS);
        return requestHeaders;
    }

    @Override
    public void requestHeaders(HttpHeaders requestHeaders) {
        if (isAvailabilityAlreadyUpdated(REQUEST_HEADERS)) {
            return;
        }

        this.requestHeaders = requireNonNull(requestHeaders, "requestHeaders");
        updateAvailability(REQUEST_HEADERS);
    }

    @Override
    public Object requestContent() {
        ensureAvailability(REQUEST_CONTENT);
        return requestContent;
    }

    @Override
    public void requestContent(@Nullable Object requestContent, @Nullable Object rawRequestContent) {
        if (isAvailabilityAlreadyUpdated(REQUEST_CONTENT)) {
            return;
        }

        this.requestContent = requestContent;
        this.rawRequestContent = rawRequestContent;
        updateAvailability(REQUEST_CONTENT);
    }

    @Override
    public Object rawRequestContent() {
        ensureAvailability(REQUEST_CONTENT);
        return rawRequestContent;
    }

    @Override
    public void deferRequestContent() {
        if (isAvailabilityAlreadyUpdated(REQUEST_CONTENT)) {
            return;
        }
        requestContentDeferred = true;
    }

    @Override
    public boolean isRequestContentDeferred() {
        return requestContentDeferred;
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
        final int flags = requestCause == null && requestContentDeferred ? FLAGS_REQUEST_END_WITHOUT_CONTENT
                                                                         : REQUEST_END.setterFlags();
        if (isAvailable(flags)) {
            return;
        }

        // if the request is not started yet, call startRequest() with requestEndTimeNanos so that
        // totalRequestDuration will be 0
        startRequest0(null, context().sessionProtocol(), null,
                      requestEndTimeNanos, currentTimeMicros(), false);

        this.requestEndTimeNanos = requestEndTimeNanos;
        this.requestCause = requestCause;
        updateAvailability(flags);
    }

    @Override
    public void startResponse() {
        startResponse0(true);
    }

    @Override
    public void startResponse(long responseStartTimeNanos, long responseStartTimeMicros) {
        startResponse0(responseStartTimeNanos, responseStartTimeMicros, true);
    }

    private void startResponse0(boolean updateAvailability) {
        startResponse0(System.nanoTime(), currentTimeMicros(), updateAvailability);
    }

    private void startResponse0(long responseStartTimeNanos, long responseStartTimeMicros,
                                boolean updateAvailability) {
        if (isAvailabilityAlreadyUpdated(RESPONSE_START)) {
            return;
        }

        this.responseStartTimeNanos = responseStartTimeNanos;
        this.responseStartTimeMicros = responseStartTimeMicros;
        if (updateAvailability) {
            updateAvailability(RESPONSE_START);
        }
    }

    @Override
    public long responseStartTimeMicros() {
        ensureAvailability(RESPONSE_START);
        return responseStartTimeMicros;
    }

    @Override
    public long responseStartTimeMillis() {
        return TimeUnit.MICROSECONDS.toMillis(responseStartTimeMicros());
    }

    @Override
    public long responseStartTimeNanos() {
        ensureAvailability(RESPONSE_START);
        return responseStartTimeNanos;
    }

    @Override
    public long responseFirstBytesTransferredTimeNanos() {
        ensureAvailability(RESPONSE_FIRST_BYTES_TRANSFERRED);
        return responseFirstBytesTransferredTimeNanos;
    }

    @Override
    public long responseEndTimeNanos() {
        ensureAvailability(RESPONSE_END);
        return responseEndTimeNanos;
    }

    @Override
    public long responseDurationNanos() {
        ensureAvailability(RESPONSE_END);
        return responseEndTimeNanos - responseStartTimeNanos;
    }

    @Override
    public Throwable responseCause() {
        ensureAvailability(RESPONSE_END);
        return responseCause;
    }

    @Override
    public long responseLength() {
        ensureAvailability(RESPONSE_END);
        return responseLength;
    }

    @Override
    public void responseLength(long responseLength) {
        if (responseLength < 0) {
            throw new IllegalArgumentException("responseLength: " + responseLength + " (expected: >= 0)");
        }

        if (isAvailable(RESPONSE_END)) {
            return;
        }

        this.responseLength = responseLength;
    }

    @Override
    public void responseFirstBytesTransferred() {
        if (isAvailabilityAlreadyUpdated(RESPONSE_FIRST_BYTES_TRANSFERRED)) {
            return;
        }
        responseFirstBytesTransferred0(System.nanoTime());
    }

    @Override
    public void responseFirstBytesTransferred(long responseFirstBytesTransferredTimeNanos) {
        if (isAvailabilityAlreadyUpdated(RESPONSE_FIRST_BYTES_TRANSFERRED)) {
            return;
        }
        responseFirstBytesTransferred0(responseFirstBytesTransferredTimeNanos);
    }

    private void responseFirstBytesTransferred0(long responseFirstBytesTransferredTimeNanos) {
        this.responseFirstBytesTransferredTimeNanos = responseFirstBytesTransferredTimeNanos;
        updateAvailability(RESPONSE_FIRST_BYTES_TRANSFERRED);
    }

    @Override
    public void increaseResponseLength(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("deltaBytes: " + deltaBytes + " (expected: >= 0)");
        }

        if (isAvailable(RESPONSE_END)) {
            return;
        }

        responseLength += deltaBytes;
    }

    @Override
    public HttpHeaders responseHeaders() {
        ensureAvailability(RESPONSE_HEADERS);
        return responseHeaders;
    }

    @Override
    public void responseHeaders(HttpHeaders responseHeaders) {
        if (isAvailabilityAlreadyUpdated(RESPONSE_HEADERS)) {
            return;
        }

        this.responseHeaders = requireNonNull(responseHeaders, "responseHeaders");
        updateAvailability(RESPONSE_HEADERS);
    }

    @Override
    public Object responseContent() {
        ensureAvailability(RESPONSE_CONTENT);
        return responseContent;
    }

    @Override
    public void responseContent(@Nullable Object responseContent, @Nullable Object rawResponseContent) {
        if (isAvailabilityAlreadyUpdated(RESPONSE_CONTENT)) {
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
        updateAvailability(RESPONSE_CONTENT);
    }

    @Override
    public Object rawResponseContent() {
        ensureAvailability(RESPONSE_CONTENT);
        return rawResponseContent;
    }

    @Override
    public void deferResponseContent() {
        if (isAvailabilityAlreadyUpdated(RESPONSE_CONTENT)) {
            return;
        }
        responseContentDeferred = true;
    }

    @Override
    public boolean isResponseContentDeferred() {
        return responseContentDeferred;
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
        final int flags = responseCause == null && responseContentDeferred ? FLAGS_RESPONSE_END_WITHOUT_CONTENT
                                                                           : RESPONSE_END.setterFlags();
        if (isAvailable(flags)) {
            return;
        }

        // if the response is not started yet, call startResponse() with responseEndTimeNanos so that
        // totalResponseDuration will be 0
        startResponse0(responseEndTimeNanos, currentTimeMicros(), false);

        this.responseEndTimeNanos = responseEndTimeNanos;
        if (this.responseCause == null) {
            this.responseCause = responseCause;
        }
        updateAvailability(flags);
    }

    @Override
    public long totalDurationNanos() {
        ensureAvailability(COMPLETE);
        return responseEndTimeNanos - requestStartTimeNanos;
    }

    private void updateAvailability(RequestLogAvailability a) {
        updateAvailability(a.setterFlags());
    }

    private void updateAvailability(int flags) {
        for (;;) {
            final int oldAvailability = this.flags;
            final int newAvailability = oldAvailability | flags;
            if (flagsUpdater.compareAndSet(this, oldAvailability, newAvailability)) {
                if (oldAvailability != newAvailability) {
                    final RequestLogListener[] satisfiedListeners;
                    synchronized (listeners) {
                        satisfiedListeners = removeSatisfiedListeners();
                    }
                    notifyListeners(satisfiedListeners);
                }
                break;
            }
        }
    }

    @Nullable
    private RequestLogListener[] removeSatisfiedListeners() {
        if (listeners.isEmpty()) {
            return null;
        }

        final int flags = this.flags;
        final int maxNumListeners = listeners.size();
        final Iterator<ListenerEntry> i = listeners.iterator();
        RequestLogListener[] satisfied = null;
        int numSatisfied = 0;

        do {
            final ListenerEntry e = i.next();
            final int interestedFlags = e.interestedFlags;
            if ((flags & interestedFlags) == interestedFlags) {
                i.remove();
                if (satisfied == null) {
                    satisfied = new RequestLogListener[maxNumListeners];
                }
                satisfied[numSatisfied++] = e.listener;
            }
        } while (i.hasNext());

        return satisfied;
    }

    private void notifyListeners(@Nullable RequestLogListener[] listeners) {
        if (listeners == null) {
            return;
        }

        for (RequestLogListener l : listeners) {
            if (l == null) {
                break;
            }
            RequestLogListenerInvoker.invokeOnRequestLog(l, this);
        }
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
    public String toStringRequestOnly() {
        return toStringRequestOnly(Function.identity(), Function.identity());
    }

    @Override
    public String toStringRequestOnly(Function<? super HttpHeaders, ? extends HttpHeaders> headersSanitizer,
                                      Function<Object, ?> contentSanitizer) {
        final int flags = this.flags & 0xFFFF; // Only interested in the bits related with request.
        if (requestStrFlags == flags) {
            return requestStr;
        }

        final StringBuilder buf = new StringBuilder(STRING_BUILDER_CAPACITY);
        buf.append('{');
        if (isAvailable(flags, REQUEST_START)) {
            buf.append("startTime=");
            TextFormatter.appendEpoch(buf, requestStartTimeMillis());

            if (isAvailable(flags, REQUEST_END)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, requestLength);
                buf.append(", duration=");
                TextFormatter.appendElapsed(buf, requestDurationNanos());
                if (requestCause != null) {
                    buf.append(", cause=").append(requestCause);
                }
            }

            buf.append(", scheme=");
            if (isAvailable(flags, SCHEME)) {
                buf.append(scheme().uriText());
            } else {
                buf.append(SerializationFormat.UNKNOWN.uriText())
                   .append('+')
                   .append(sessionProtocol.uriText());
            }

            if (isAvailable(flags, REQUEST_HEADERS)) {
                buf.append(", headers=").append(headersSanitizer.apply(requestHeaders));
            }

            if (isAvailable(flags, REQUEST_CONTENT) && requestContent != null) {
                buf.append(", content=").append(contentSanitizer.apply(requestContent));
            }
        }
        buf.append('}');

        requestStr = buf.toString();
        requestStrFlags = flags;

        return requestStr;
    }

    @Override
    public String toStringResponseOnly() {
        return toStringResponseOnly(Function.identity(), Function.identity());
    }

    @Override
    public String toStringResponseOnly(Function<? super HttpHeaders, ? extends HttpHeaders> headersSanitizer,
                                       Function<Object, ?> contentSanitizer) {

        final int flags = this.flags & 0xFFFF0000; // Only interested in the bits related with response.
        if (responseStrFlags == flags) {
            return responseStr;
        }

        final StringBuilder buf = new StringBuilder(STRING_BUILDER_CAPACITY);
        buf.append('{');
        if (isAvailable(flags, RESPONSE_START)) {
            buf.append("startTime=");
            TextFormatter.appendEpoch(buf, responseStartTimeMillis());

            if (isAvailable(flags, RESPONSE_END)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, responseLength);
                buf.append(", duration=");
                TextFormatter.appendElapsed(buf, responseDurationNanos());
                if (isAvailable(flags, REQUEST_START)) {
                    buf.append(", totalDuration=");
                    TextFormatter.appendElapsed(buf, totalDurationNanos());
                }
                if (responseCause != null) {
                    buf.append(", cause=").append(responseCause);
                }
            }

            if (isAvailable(flags, RESPONSE_HEADERS)) {
                buf.append(", headers=").append(headersSanitizer.apply(responseHeaders));
            }

            if (isAvailable(flags, RESPONSE_CONTENT) && responseContent != null) {
                buf.append(", content=").append(contentSanitizer.apply(responseContent));
            }
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

    private static final class ListenerEntry {
        final RequestLogListener listener;
        final int interestedFlags;

        ListenerEntry(RequestLogListener listener, int interestedFlags) {
            this.listener = listener;
            this.interestedFlags = interestedFlags;
        }
    }

    private static long currentTimeMicros() {
        if (PlatformDependent.javaVersion() == 8) {
            return TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis());
        } else {
            // Java 9+ support higher precision wall time.
            final Instant now = Clock.systemUTC().instant();
            return TimeUnit.SECONDS.toMicros(now.getEpochSecond()) + TimeUnit.NANOSECONDS.toMicros(
                    now.getNano());
        }
    }
}
