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

import static com.linecorp.armeria.common.logging.RequestLogAvailability.COMPLETE;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_END;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_END;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.SCHEME;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

/**
 * Default {@link RequestLog} implementation.
 */
public class DefaultRequestLog extends AbstractRequestLog implements RequestLogBuilder {

    private static final AtomicIntegerFieldUpdater<DefaultRequestLog> flagsUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRequestLog.class, "flags");

    private static final int FLAGS_REQUEST_END_WITHOUT_CONTENT =
            REQUEST_END.setterFlags() & ~REQUEST_CONTENT.setterFlags();
    private static final int FLAGS_RESPONSE_END_WITHOUT_CONTENT =
            RESPONSE_END.setterFlags() & ~RESPONSE_CONTENT.setterFlags();

    /**
     * Updated by {@link #flagsUpdater}.
     */
    @SuppressWarnings("unused")
    private volatile int flags;
    private final List<ListenerEntry> listeners = new ArrayList<>(4);
    private volatile boolean requestContentDeferred;
    private volatile boolean responseContentDeferred;

    private long requestStartTimeMillis;
    private long requestStartTimeNanos;
    private long requestEndTimeNanos;
    private long requestLength;
    private Throwable requestCause;

    private long responseStartTimeMillis;
    private long responseStartTimeNanos;
    private long responseEndTimeNanos;
    private long responseLength;
    private Throwable responseCause;

    private Channel channel;
    private SessionProtocol sessionProtocol;
    private SerializationFormat serializationFormat = SerializationFormat.NONE;
    private String host;

    private HttpHeaders requestHeaders = HttpHeaders.EMPTY_HEADERS;
    private HttpHeaders responseHeaders = HttpHeaders.EMPTY_HEADERS;
    private Object requestContent;
    private Object rawRequestContent;
    private Object responseContent;
    private Object rawResponseContent;

    /**
     * Creates a new instance.
     */
    public DefaultRequestLog(RequestContext ctx) {
        super(ctx);
    }

    @Override
    public int flags() {
        return flags;
    }

    void addListener(ListenerEntry entry) {
        requireNonNull(entry, "entry");
        if (entry.interestedFlags() == 0) {
            throw new IllegalArgumentException("no availability specified");
        }

        if (isAvailable(entry.interestedFlags())) {
            // No need to add to 'listeners'.
            RequestLogListenerInvoker.invokeOnRequestLog(entry.listener(), this);
            return;
        }

        final RequestLogListener[] satisfiedListeners;
        synchronized (listeners) {
            listeners.add(entry);
            satisfiedListeners = removeSatisfiedListeners();
        }
        notifyListeners(satisfiedListeners);
    }

    @Override
    public void startRequest(Channel channel, SessionProtocol sessionProtocol, String host) {
        requireNonNull(channel, "channel");
        requireNonNull(sessionProtocol, "sessionProtocol");
        requireNonNull(host, "host");
        startRequest0(channel, sessionProtocol, host, true);
    }

    private void startRequest0(Channel channel, SessionProtocol sessionProtocol,
                               String host, boolean updateAvailability) {

        if (isAvailabilityAlreadyUpdated(REQUEST_START)) {
            return;
        }

        requestStartTimeNanos = System.nanoTime();
        requestStartTimeMillis = System.currentTimeMillis();
        this.channel = channel;
        this.sessionProtocol = sessionProtocol;
        this.host = host;

        if (updateAvailability) {
            updateAvailability(REQUEST_START);
        }
    }

    private boolean isAvailabilityAlreadyUpdated(RequestLogAvailability availability) {
        return isAvailable(availability.setterFlags());
    }

    @Override
    public long requestStartTimeMillis() {
        ensureAvailability(REQUEST_START);
        return requestStartTimeMillis;
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
    public SessionProtocol sessionProtocol() {
        ensureAvailability(REQUEST_START);
        return sessionProtocol;
    }

    @Override
    public String host() {
        ensureAvailability(REQUEST_START);
        return host;
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

    private void endRequest0(Throwable requestCause) {
        final int flags = requestCause == null && requestContentDeferred ? FLAGS_REQUEST_END_WITHOUT_CONTENT
                                                                         : REQUEST_END.setterFlags();
        if (isAvailable(flags)) {
            return;
        }

        startRequest0(null, context().sessionProtocol(), null, false);
        requestEndTimeNanos = System.nanoTime();
        this.requestCause = requestCause;
        updateAvailability(flags);
    }

    @Override
    public void startResponse() {
        startResponse0(true);
    }

    private void startResponse0(boolean updateAvailability) {
        if (isAvailabilityAlreadyUpdated(RESPONSE_START)) {
            return;
        }

        responseStartTimeNanos = System.nanoTime();
        responseStartTimeMillis = System.currentTimeMillis();
        if (updateAvailability) {
            updateAvailability(RESPONSE_START);
        }
    }

    @Override
    public long responseStartTimeMillis() {
        ensureAvailability(RESPONSE_START);
        return responseStartTimeMillis;
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

        if (responseContent instanceof RpcResponse &&
            !((RpcResponse) responseContent).isDone()) {
            throw new IllegalArgumentException("responseContent must be complete: " + responseContent);
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

    private void endResponse0(Throwable responseCause) {
        final int flags = responseCause == null && responseContentDeferred ? FLAGS_RESPONSE_END_WITHOUT_CONTENT
                                                                           : RESPONSE_END.setterFlags();
        if (isAvailable(flags)) {
            return;
        }

        startResponse0(false);
        responseEndTimeNanos = System.nanoTime();
        this.responseCause = responseCause;
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
            final int interestedFlags = e.interestedFlags();
            if ((flags & interestedFlags) == interestedFlags) {
                i.remove();
                if (satisfied == null) {
                    satisfied = new RequestLogListener[maxNumListeners];
                }
                satisfied[numSatisfied++] = e.listener();
            }
        } while (i.hasNext());

        return satisfied;
    }

    private void notifyListeners(RequestLogListener[] listeners) {
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
}
