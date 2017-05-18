/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_ENVELOPE;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_END;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_ENVELOPE;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.SCHEME;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.STATUS_CODE;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.util.TextFormatter;

import io.netty.channel.Channel;

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

    private final RequestContext ctx;

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
    private HttpMethod method;
    private String path;
    private String query;
    private int statusCode = -1;

    private Object requestEnvelope;
    private Object responseEnvelope;
    private Object requestContent;
    private Object rawRequestContent;
    private Object responseContent;
    private Object rawResponseContent;

    private volatile int requestStrFlags = -1;
    private volatile int responseStrFlags = -1;
    private String requestStr;
    private String responseStr;

    /**
     * Creates a new instance.
     */
    public DefaultRequestLog(RequestContext ctx) {
        this.ctx = requireNonNull(ctx, "ctx");
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
                             String host, HttpMethod method, String path, String query) {

        startRequest0(channel, sessionProtocol, host, method, path, query, true);
    }

    private void startRequest0(Channel channel, SessionProtocol sessionProtocol,
                               String host, HttpMethod method, String path, String query,
                               boolean updateAvailability) {

        if (isAvailabilityAlreadyUpdated(REQUEST_START)) {
            return;
        }

        requestStartTimeNanos = System.nanoTime();
        requestStartTimeMillis = System.currentTimeMillis();
        this.channel = channel;
        this.sessionProtocol = sessionProtocol;
        this.host = host;
        this.method = method;
        this.path = path;
        this.query = query;

        if (updateAvailability) {
            updateAvailability(REQUEST_START);
        }
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
    public HttpMethod method() {
        ensureAvailability(REQUEST_START);
        return method;
    }

    @Override
    public String path() {
        ensureAvailability(REQUEST_START);
        return path;
    }

    @Override
    public String query() {
        ensureAvailability(REQUEST_START);
        return query;
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
    public Object requestEnvelope() {
        ensureAvailability(REQUEST_ENVELOPE);
        return requestEnvelope;
    }

    @Override
    public void requestEnvelope(Object requestEnvelope) {
        if (isAvailabilityAlreadyUpdated(REQUEST_ENVELOPE)) {
            return;
        }

        this.requestEnvelope = requestEnvelope;
        updateAvailability(REQUEST_ENVELOPE);
    }

    @Override
    public Object requestContent() {
        ensureAvailability(REQUEST_CONTENT);
        return requestContent;
    }

    @Override
    public void requestContent(Object requestContent, Object rawRequestContent) {
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

        startRequest0(null, null, null, null, null, null, false);
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
    public int statusCode() {
        ensureAvailability(STATUS_CODE);
        return statusCode;
    }

    @Override
    public void statusCode(int statusCode) {
        if (isAvailabilityAlreadyUpdated(STATUS_CODE)) {
            return;
        }

        this.statusCode = statusCode;
        updateAvailability(STATUS_CODE);
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
    public Object responseEnvelope() {
        ensureAvailability(RESPONSE_ENVELOPE);
        return responseEnvelope;
    }

    @Override
    public void responseEnvelope(Object responseEnvelope) {
        if (isAvailabilityAlreadyUpdated(RESPONSE_ENVELOPE)) {
            return;
        }

        this.responseEnvelope = responseEnvelope;
        updateAvailability(RESPONSE_ENVELOPE);
    }

    @Override
    public Object responseContent() {
        ensureAvailability(RESPONSE_CONTENT);
        return responseContent;
    }

    @Override
    public void responseContent(Object responseContent, Object rawResponseContent) {
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
        endResponse0(null);
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

    @Override
    public String toString() {
        final String req = toStringRequestOnly();
        final String res = toStringResponseOnly();
        final StringBuilder buf = new StringBuilder(5 + req.length() + 6 + res.length() + 1);
        return buf.append("{req=")  // 5
                  .append(req)
                  .append(", res=") // 6
                  .append(res)
                  .append('}')      // 1
                  .toString();
    }

    @Override
    public String toStringRequestOnly() {
        final int flags = this.flags & 0xFFFF; // Only interested in the bits related with request.
        if (requestStrFlags == flags) {
            return requestStr;
        }

        final StringBuilder buf = new StringBuilder(STRING_BUILDER_CAPACITY);
        buf.append('{');
        if (isAvailable(flags, REQUEST_START)) {
            buf.append("startTime=");
            TextFormatter.appendEpoch(buf, requestStartTimeMillis);

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

            buf.append(", host=").append(host)
               .append(", method=").append(method)
               .append(", path=").append(path);

            if (isAvailable(flags, REQUEST_ENVELOPE) && requestEnvelope != null) {
                buf.append(", envelope=").append(requestEnvelope);
            }

            if (isAvailable(flags, REQUEST_CONTENT) && requestContent != null) {
                buf.append(", content=").append(requestContent);
            }
        }
        buf.append('}');

        requestStr = buf.toString();
        requestStrFlags = flags;

        return requestStr;
    }

    @Override
    public String toStringResponseOnly() {
        final int flags = this.flags & 0xFFFF0000; // Only interested in the bits related with response.
        if (responseStrFlags == flags) {
            return responseStr;
        }

        final StringBuilder buf = new StringBuilder(STRING_BUILDER_CAPACITY);
        buf.append('{');
        if (isAvailable(flags, RESPONSE_START)) {
            buf.append("startTime=");
            TextFormatter.appendEpoch(buf, responseStartTimeMillis);

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

            if (isAvailable(flags, STATUS_CODE)) {
                buf.append(", statusCode=").append(statusCode);
            }

            if (isAvailable(flags, RESPONSE_ENVELOPE) && responseEnvelope != null) {
                buf.append(", envelope=").append(responseEnvelope);
            }

            if (isAvailable(flags, RESPONSE_CONTENT) && responseContent != null) {
                buf.append(", content=").append(responseContent);
            }
        }
        buf.append('}');

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
}
