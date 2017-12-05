/*
 * Copyright 2017 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

/**
 * A {@link RequestLog} implementation used for the client who has {@link RetryingClient} as its decorator.
 * If the {@link LoggingClient} is wrapping the {@link RetryingClient}, only the first request and
 * the last response will be recorded. If the order is the opposite, all of the requests and
 * responses will be recorded.
 * i.e.
 * <ul>
 *   <li>Client - LoggingClient - RetryingClient - Socket
 *   (only the first request and the last response will be recorded)</li>
 *   <li>Client - RetryingClient - LoggingClient - Socket
 *   (all of the requests and responses will be recorded)</li>
 * </ul>
 */
public class RetryingRequestLog extends AbstractRequestLog {

    private final List<ListenerEntry> listeners = new ArrayList<>(4);
    private final List<LogAndListenerEntry> logStore = new ArrayList<>(4);

    private final DefaultRequestLog firstLog;

    private DefaultRequestLog currentLog;
    private boolean inRetrying;

    /**
     * Creates a new instance.
     */
    public RetryingRequestLog(RequestContext ctx) {
        this(ctx, new DefaultRequestLog(requireNonNull(ctx, "ctx")));
    }

    private RetryingRequestLog(RequestContext ctx, DefaultRequestLog log) {
        this(ctx, log, log);
    }

    private RetryingRequestLog(RequestContext ctx, DefaultRequestLog firstLog, DefaultRequestLog currentLog) {
        super(ctx);
        this.firstLog = firstLog;
        this.currentLog = currentLog;
    }

    /**
     * Creates a new {@link DefaultRequestLog} and set it as the current log. After this is invoked all of the
     * log information will be updated to the current log.
     */
    public void newCurrentLog() {
        currentLog = new DefaultRequestLog(context());

        // remove the logs from the previous response
        logStore.clear();

        for (ListenerEntry entry : listeners) {
            addDelayableListener(entry);
        }
    }

    private void addDelayableListener(ListenerEntry entry) {
        currentLog.addListener(
                log -> {
                    if (!(log instanceof DefaultRequestLog)) {
                        throw new IllegalStateException("callback log should be a DefaultRequestLog");
                    }
                    final RequestLog callbackLog = new CallbackLog(firstLog, (DefaultRequestLog) log);
                    if (inRetrying) {
                        // store the callback logs if in retrying
                        logStore.add(new LogAndListenerEntry(callbackLog, entry));
                    } else {
                        // invoke the log right away when not in retrying
                        RequestLogListenerInvoker.invokeOnRequestLog(entry.listener(), callbackLog);
                    }
                },
                RequestLogAvailabilitySet.of(entry.interestedFlags()));
    }

    /**
     * Marks this {@link RetryingRequestLog} as in the retrying session.
     */
    public void inRetrying() {
        this.inRetrying = true;
    }

    /**
     * Should be called when retrying session ends.
     */
    public void endRetrying() {
        inRetrying = false;
        if (logStore.isEmpty()) {
            return;
        }

        logStore.forEach(logAndListenerEntry -> {
            RequestLogListenerInvoker.invokeOnRequestLog(
                    logAndListenerEntry.listenerEntry.listener(), logAndListenerEntry.log);
        });
    }

    @Override
    public int flags() {
        return currentLog.flags();
    }

    @Override
    public Set<RequestLogAvailability> availabilities() {
        return currentLog.availabilities();
    }

    void addListener(ListenerEntry entry) {
        requireNonNull(entry, "entry");
        if (inRetrying || RequestLogAvailability.isRequestAvailabilityOnly(entry.interestedFlags())) {
            currentLog.addListener(entry.listener(), RequestLogAvailabilitySet.of(entry.interestedFlags()));
        } else {
            addDelayableListener(entry);
            listeners.add(entry);
        }
    }

    @Override
    public long requestStartTimeMillis() {
        if (inRetrying) {
            return currentLog.requestStartTimeMillis();
        }
        return firstLog.requestStartTimeMillis();
    }

    @Override
    public long requestDurationNanos() {
        if (inRetrying) {
            return currentLog.requestDurationNanos();
        }
        return firstLog.requestDurationNanos();
    }

    @Override
    public long requestLength() {
        if (inRetrying) {
            return currentLog.requestLength();
        }
        return firstLog.requestLength();
    }

    @Override
    public void requestLength(long requestLength) {
        currentLog.requestLength(requestLength);
    }

    @Nullable
    @Override
    public Throwable requestCause() {
        if (inRetrying) {
            return currentLog.requestCause();
        }
        return firstLog.requestCause();
    }

    @Override
    public HttpHeaders requestHeaders() {
        if (inRetrying) {
            return currentLog.requestHeaders();
        }
        return firstLog.requestHeaders();
    }

    @Override
    public void requestHeaders(HttpHeaders requestHeaders) {
        currentLog.requestHeaders(requestHeaders);
    }

    @Nullable
    @Override
    public Object requestContent() {
        if (inRetrying) {
            return currentLog.requestContent();
        }
        return firstLog.requestContent();
    }

    @Override
    public void requestContent(@Nullable Object requestContent, @Nullable Object rawRequestContent) {
        currentLog.requestContent(requestContent, rawRequestContent);
    }

    @Nullable
    @Override
    public Object rawRequestContent() {
        if (inRetrying) {
            return currentLog.rawRequestContent();
        }
        return firstLog.rawRequestContent();
    }

    @Override
    public String toStringRequestOnly(Function<HttpHeaders, HttpHeaders> headersSanitizer,
                                      Function<Object, Object> contentSanitizer) {
        if (inRetrying) {
            return currentLog.toStringRequestOnly(headersSanitizer, contentSanitizer);
        }
        return firstLog.toStringRequestOnly(headersSanitizer, contentSanitizer);
    }

    // The response related methods and the log builder methods are delegated to the currentLog.
    @Override
    public long responseStartTimeMillis() {
        return currentLog.responseStartTimeMillis();
    }

    @Override
    public long responseDurationNanos() {
        return currentLog.responseDurationNanos();
    }

    @Override
    public long responseLength() {
        return currentLog.responseLength();
    }

    @Override
    public void responseLength(long responseLength) {
        currentLog.responseLength(responseLength);
    }

    @Nullable
    @Override
    public Throwable responseCause() {
        return currentLog.responseCause();
    }

    @Override
    public long totalDurationNanos() {
        return currentLog.totalDurationNanos();
    }

    @Nullable
    @Override
    public Channel channel() {
        return currentLog.channel();
    }

    @Override
    public SessionProtocol sessionProtocol() {
        return currentLog.sessionProtocol();
    }

    @Override
    public SerializationFormat serializationFormat() {
        return currentLog.serializationFormat();
    }

    @Override
    public void serializationFormat(SerializationFormat serializationFormat) {
        currentLog.serializationFormat(serializationFormat);
    }

    @Override
    public Scheme scheme() {
        return currentLog.scheme();
    }

    @Nullable
    @Override
    public String host() {
        return currentLog.host();
    }

    @Override
    public HttpHeaders responseHeaders() {
        return currentLog.responseHeaders();
    }

    @Override
    public void responseHeaders(HttpHeaders responseHeaders) {
        currentLog.responseHeaders(responseHeaders);
    }

    @Nullable
    @Override
    public Object responseContent() {
        return currentLog.responseHeaders();
    }

    @Override
    public void responseContent(@Nullable Object responseContent, @Nullable Object rawResponseContent) {
        currentLog.responseContent(responseContent, rawResponseContent);
    }

    @Nullable
    @Override
    public Object rawResponseContent() {
        return currentLog.rawResponseContent();
    }

    @Override
    public String toStringResponseOnly(Function<HttpHeaders, HttpHeaders> headersSanitizer,
                                       Function<Object, Object> contentSanitizer) {
        return currentLog.toStringResponseOnly(headersSanitizer, contentSanitizer);
    }

    @Override
    public void startRequest(Channel channel, SessionProtocol sessionProtocol, String host) {
        currentLog.startRequest(channel, sessionProtocol, host);
    }

    @Override
    public void increaseRequestLength(long deltaBytes) {
        currentLog.increaseRequestLength(deltaBytes);
    }

    @Override
    public void deferRequestContent() {
        currentLog.deferRequestContent();
    }

    @Override
    public boolean isRequestContentDeferred() {
        return currentLog.isRequestContentDeferred();
    }

    @Override
    public void endRequest() {
        currentLog.endRequest();
    }

    @Override
    public void endRequest(Throwable requestCause) {
        currentLog.endRequest(requestCause);
    }

    @Override
    public void startResponse() {
        currentLog.startResponse();
    }

    @Override
    public void increaseResponseLength(long deltaBytes) {
        currentLog.increaseResponseLength(deltaBytes);
    }

    @Override
    public void deferResponseContent() {
        currentLog.deferResponseContent();
    }

    @Override
    public boolean isResponseContentDeferred() {
        return currentLog.isResponseContentDeferred();
    }

    @Override
    public void endResponse() {
        currentLog.endResponse();
    }

    @Override
    public void endResponse(Throwable responseCause) {
        currentLog.endResponse(responseCause);
    }

    private static class LogAndListenerEntry {
        final RequestLog log;
        final ListenerEntry listenerEntry;

        LogAndListenerEntry(RequestLog log, ListenerEntry listenerEntry) {
            this.log = log;
            this.listenerEntry = listenerEntry;
        }
    }

    private static class CallbackLog extends RetryingRequestLog {
        private final int callbackFlags;

        CallbackLog(DefaultRequestLog firstLog, DefaultRequestLog currentLog) {
            super(currentLog.context(), firstLog, currentLog);
            callbackFlags = currentLog.flags();
        }

        @Override
        public int flags() {
            return callbackFlags;
        }

        @Override
        public void addListener(RequestLogListener listener, RequestLogAvailability availability) {
            throw new UnsupportedOperationException("cannot add a listener to the callback request log");
        }

        @Override
        public void addListener(RequestLogListener listener, RequestLogAvailability... availabilities) {
            throw new UnsupportedOperationException("cannot add a listener to the callback request log");
        }

        @Override
        public void addListener(RequestLogListener listener, Iterable<RequestLogAvailability> availabilities) {
            throw new UnsupportedOperationException("cannot add a listener to the callback request log");
        }
    }
}
