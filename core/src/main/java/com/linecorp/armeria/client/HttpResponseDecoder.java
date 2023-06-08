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

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSession;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.ContentTooLargeExceptionBuilder;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.EventExecutor;

abstract class HttpResponseDecoder {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseDecoder.class);

    private static final RequestLogBuilder NOOP_REQUEST_LOG_BUILDER = new NoopRequestLog();
    private static final HttpRequest NOOP_HTTP_REQUEST = new NoopHttpRequest();

    private final IntObjectMap<HttpResponseWrapper> responses = new IntObjectHashMap<>();
    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;

    @Nullable
    private HttpSession httpSession;

    private int unfinishedResponses;
    private boolean closing;

    HttpResponseDecoder(Channel channel, InboundTrafficController inboundTrafficController) {
        this.channel = channel;
        this.inboundTrafficController = inboundTrafficController;
    }

    final Channel channel() {
        return channel;
    }

    final InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    HttpResponseWrapper addResponse(
            int id, DecodedHttpResponse res, @Nullable ClientRequestContext ctx,
            EventLoop eventLoop, long responseTimeoutMillis, long maxContentLength) {
        final HttpResponseWrapper newRes;
        if (ctx != null) {
            final HttpRequest request = ctx.request();
            assert request != null;
            newRes = new HttpResponseWrapper(
                    res, ctx, ctx.logBuilder(), request, responseTimeoutMillis, maxContentLength);
        } else {
            newRes = new HttpResponseWrapper(
                    res, null, NOOP_REQUEST_LOG_BUILDER, NOOP_HTTP_REQUEST,
                    responseTimeoutMillis, maxContentLength);
        }

        final HttpResponseWrapper oldRes = responses.put(id, newRes);

        final KeepAliveHandler keepAliveHandler = keepAliveHandler();
        if (keepAliveHandler != null) {
            keepAliveHandler.increaseNumRequests();
        }

        assert oldRes == null : "addResponse(" + id + ", " + res + ", " + responseTimeoutMillis + "): " +
                                oldRes;
        onResponseAdded(id, eventLoop, newRes);
        return newRes;
    }

    abstract void onResponseAdded(int id, EventLoop eventLoop, HttpResponseWrapper responseWrapper);

    @Nullable
    final HttpResponseWrapper getResponse(int id) {
        return responses.get(id);
    }

    @Nullable
    final HttpResponseWrapper removeResponse(int id) {
        if (closing) {
            // `unfinishedResponses` will be removed by `failUnfinishedResponses()`
            return null;
        }

        final HttpResponseWrapper removed = responses.remove(id);
        if (removed != null) {
            unfinishedResponses--;
            assert unfinishedResponses >= 0 : unfinishedResponses;
        }
        return removed;
    }

    final boolean hasUnfinishedResponses() {
        return unfinishedResponses != 0;
    }

    final boolean reserveUnfinishedResponse(int maxUnfinishedResponses) {
        if (unfinishedResponses >= maxUnfinishedResponses) {
            return false;
        }

        unfinishedResponses++;
        return true;
    }

    final void decrementUnfinishedResponses() {
        unfinishedResponses--;
    }

    final void failUnfinishedResponses(Throwable cause) {
        if (closing) {
            return;
        }
        closing = true;

        for (final Iterator<HttpResponseWrapper> iterator = responses.values().iterator();
             iterator.hasNext();) {
            final HttpResponseWrapper res = iterator.next();
            // To avoid calling removeResponse by res.close(cause), remove before closing.
            iterator.remove();
            unfinishedResponses--;
            res.close(cause);
        }
    }

    HttpSession session() {
        if (httpSession != null) {
            return httpSession;
        }
        return httpSession = HttpSession.get(channel);
    }

    @Nullable
    abstract KeepAliveHandler keepAliveHandler();

    final boolean needsToDisconnectNow() {
        return !session().isAcquirable() && !hasUnfinishedResponses();
    }

    static final class HttpResponseWrapper implements StreamWriter<HttpObject> {

        private final DecodedHttpResponse delegate;
        @Nullable
        private final ClientRequestContext ctx;

        private final RequestLogBuilder logBuilder;
        private final HttpRequest request;
        private final long maxContentLength;
        private final long responseTimeoutMillis;

        private boolean responseStarted;
        private long contentLengthHeaderValue = -1;

        private boolean done;

        HttpResponseWrapper(DecodedHttpResponse delegate, @Nullable ClientRequestContext ctx,
                            RequestLogBuilder logBuilder, HttpRequest request,
                            long responseTimeoutMillis, long maxContentLength) {
            this.delegate = delegate;
            this.ctx = ctx;
            this.logBuilder = logBuilder;
            this.request = request;
            this.maxContentLength = maxContentLength;
            this.responseTimeoutMillis = responseTimeoutMillis;
        }

        long maxContentLength() {
            return maxContentLength;
        }

        long writtenBytes() {
            return delegate.writtenBytes();
        }

        long contentLengthHeaderValue() {
            return contentLengthHeaderValue;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean isEmpty() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long demand() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> whenComplete() {
            return delegate.whenComplete();
        }

        @Override
        public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                              SubscriptionOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort(Throwable cause) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryWrite(HttpObject o) {
            if (done) {
                PooledObjects.close(o);
                return false;
            }
            return delegate.tryWrite(o);
        }

        void startResponse() {
            if (responseStarted) {
                return;
            }
            responseStarted = true;
            logBuilder.startResponse();
            logBuilder.responseFirstBytesTransferred();
            initTimeout();
        }

        boolean tryWriteResponseHeaders(ResponseHeaders responseHeaders) {
            assert responseHeaders.status().codeClass() != HttpStatusClass.INFORMATIONAL;
            contentLengthHeaderValue = responseHeaders.contentLength();
            logBuilder.defer(RequestLogProperty.RESPONSE_HEADERS);
            try {
                return delegate.tryWrite(responseHeaders);
            } finally {
                logBuilder.responseHeaders(responseHeaders);
            }
        }

        boolean tryWriteData(HttpData data) {
            if (done) {
                PooledObjects.close(data);
                return false;
            }
            data.touch(ctx);
            logBuilder.increaseResponseLength(data);
            return delegate.tryWrite(data);
        }

        boolean tryWriteTrailers(HttpHeaders trailers) {
            if (done) {
                return false;
            }
            done = true;
            logBuilder.defer(RequestLogProperty.RESPONSE_TRAILERS);
            try {
                return delegate.tryWrite(trailers);
            } finally {
                logBuilder.responseTrailers(trailers);
            }
        }

        @Override
        public CompletableFuture<Void> whenConsumed() {
            return delegate.whenConsumed();
        }

        void onSubscriptionCancelled(@Nullable Throwable cause) {
            close(cause, true);
        }

        @Override
        public void close() {
            close(null, false);
        }

        @Override
        public void close(Throwable cause) {
            close(cause, false);
        }

        private void close(@Nullable Throwable cause, boolean cancel) {
            done = true;
            cancelTimeoutOrLog(cause, cancel);
            if (cause == null) {
                request.abort(ResponseCompleteException.get());
            } else {
                request.abort(cause);
            }
        }

        private void closeAction(@Nullable Throwable cause) {
            if (cause != null) {
                delegate.close(cause);
                logBuilder.endResponse(cause);
            } else {
                delegate.close();
                logBuilder.endResponse();
            }
        }

        private void cancelAction(@Nullable Throwable cause) {
            if (cause != null && !(cause instanceof CancelledSubscriptionException)) {
                logBuilder.endResponse(cause);
            } else {
                logBuilder.endResponse();
            }
        }

        private void cancelTimeoutOrLog(@Nullable Throwable cause, boolean cancel) {
            CancellationScheduler responseCancellationScheduler = null;
            if (ctx != null) {
                final ClientRequestContextExtension ctxExtension = ctx.as(ClientRequestContextExtension.class);
                if (ctxExtension != null) {
                    responseCancellationScheduler = ctxExtension.responseCancellationScheduler();
                }
            }

            if (responseCancellationScheduler == null || !responseCancellationScheduler.isFinished()) {
                if (responseCancellationScheduler != null) {
                    responseCancellationScheduler.clearTimeout(false);
                }
                // There's no timeout or the response has not been timed out.
                if (cancel) {
                    cancelAction(cause);
                } else {
                    closeAction(cause);
                }
                return;
            }
            if (delegate.isOpen()) {
                closeAction(cause);
            }

            // Response has been timed out already.
            // Log only when it's not a ResponseTimeoutException.
            if (cause instanceof ResponseTimeoutException) {
                return;
            }

            if (cause == null || !logger.isWarnEnabled() || Exceptions.isExpected(cause)) {
                return;
            }

            final StringBuilder logMsg = new StringBuilder("Unexpected exception while closing a request");
            final String authority = request.authority();
            if (authority != null) {
                logMsg.append(" to ").append(authority);
            }

            logger.warn(logMsg.append(':').toString(), cause);
        }

        void initTimeout() {
            if (ctx == null) {
                return;
            }
            final ClientRequestContextExtension ctxExtension = ctx.as(ClientRequestContextExtension.class);
            if (ctxExtension != null) {
                final CancellationScheduler responseCancellationScheduler =
                        ctxExtension.responseCancellationScheduler();
                responseCancellationScheduler.init(
                        ctx.eventLoop(), newCancellationTask(),
                        TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis), /* server */ false);
            }
        }

        private CancellationTask newCancellationTask() {
            return new CancellationTask() {
                @Override
                public boolean canSchedule() {
                    return delegate.isOpen() && !done;
                }

                @Override
                public void run(Throwable cause) {
                    delegate.close(cause);
                    request.abort(cause);
                    logBuilder.endResponse(cause);
                }
            };
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    static Exception contentTooLargeException(HttpResponseWrapper res, long transferred) {
        final ContentTooLargeExceptionBuilder builder =
                ContentTooLargeException.builder()
                                        .maxContentLength(res.maxContentLength())
                                        .transferred(transferred);
        if (res.contentLengthHeaderValue() >= 0) {
            builder.contentLength(res.contentLengthHeaderValue());
        }
        return builder.build();
    }

    private static class NoopRequestLog implements RequestLogBuilder {

        @Override
        public boolean isComplete() {
            return false;
        }

        @Override
        public boolean isRequestComplete() {
            return false;
        }

        @Override
        public boolean isAvailable(RequestLogProperty property) {
            return false;
        }

        @Override
        public boolean isAvailable(RequestLogProperty... properties) {
            return false;
        }

        @Override
        public boolean isAvailable(Iterable<RequestLogProperty> properties) {
            return false;
        }

        @Nullable
        @Override
        public CompletableFuture<RequestLog> whenComplete() {
            return null;
        }

        @Nullable
        @Override
        public CompletableFuture<RequestOnlyLog> whenRequestComplete() {
            return null;
        }

        @Nullable
        @Override
        public CompletableFuture<RequestLog> whenAvailable(RequestLogProperty property) {
            return null;
        }

        @Nullable
        @Override
        public CompletableFuture<RequestLog> whenAvailable(RequestLogProperty... properties) {
            return null;
        }

        @Nullable
        @Override
        public CompletableFuture<RequestLog> whenAvailable(Iterable<RequestLogProperty> properties) {
            return null;
        }

        @Nullable
        @Override
        public RequestLog ensureComplete() {
            return null;
        }

        @Nullable
        @Override
        public RequestOnlyLog ensureRequestComplete() {
            return null;
        }

        @Nullable
        @Override
        public RequestLog ensureAvailable(RequestLogProperty property) {
            return null;
        }

        @Nullable
        @Override
        public RequestLog ensureAvailable(RequestLogProperty... properties) {
            return null;
        }

        @Nullable
        @Override
        public RequestLog ensureAvailable(Iterable<RequestLogProperty> properties) {
            return null;
        }

        @Nullable
        @Override
        public RequestLog partial() {
            return null;
        }

        @Nullable
        @Override
        public int availabilityStamp() {
            return 0;
        }

        @Nullable
        @Override
        public RequestContext context() {
            return null;
        }

        @Nullable
        @Override
        public RequestLogAccess parent() {
            return null;
        }

        @Nullable
        @Override
        public List<RequestLogAccess> children() {
            return null;
        }

        @Override
        public void startRequest(long requestStartTimeNanos, long requestStartTimeMicros) {}

        @Override
        public void session(@Nullable Channel channel, SessionProtocol sessionProtocol,
                            @Nullable ClientConnectionTimings connectionTimings) {}

        @Override
        public void session(@Nullable Channel channel, SessionProtocol sessionProtocol,
                            @Nullable SSLSession sslSession,
                            @Nullable ClientConnectionTimings connectionTimings) {}

        @Override
        public void serializationFormat(SerializationFormat serializationFormat) {}

        @Override
        public void name(String serviceName, String name) {}

        @Override
        public void name(String name) {}

        @Override
        public void authenticatedUser(String authenticatedUser) {}

        @Override
        public void increaseRequestLength(long deltaBytes) {}

        @Override
        public void increaseRequestLength(HttpData data) {}

        @Override
        public void requestLength(long requestLength) {}

        @Override
        public void requestFirstBytesTransferred() {}

        @Override
        public void requestFirstBytesTransferred(long requestFirstBytesTransferredNanos) {}

        @Override
        public void requestHeaders(RequestHeaders requestHeaders) {}

        @Override
        public void requestContent(@Nullable Object requestContent, @Nullable Object rawRequestContent) {}

        @Override
        public void requestContentPreview(@Nullable String requestContentPreview) {}

        @Override
        public void requestTrailers(HttpHeaders requestTrailers) {}

        @Override
        public void endRequest() {}

        @Override
        public void endRequest(Throwable requestCause) {}

        @Override
        public void endRequest(long requestEndTimeNanos) {}

        @Override
        public void endRequest(Throwable requestCause, long requestEndTimeNanos) {}

        @Override
        public void startResponse() {}

        @Override
        public void startResponse(long responseStartTimeNanos, long responseStartTimeMicros) {}

        @Override
        public void increaseResponseLength(long deltaBytes) {}

        @Override
        public void increaseResponseLength(HttpData data) {}

        @Override
        public void responseLength(long responseLength) {}

        @Override
        public void responseFirstBytesTransferred() {}

        @Override
        public void responseFirstBytesTransferred(long responseFirstBytesTransferredNanos) {}

        @Override
        public void responseHeaders(ResponseHeaders responseHeaders) {}

        @Override
        public void responseContent(@Nullable Object responseContent, @Nullable Object rawResponseContent) {}

        @Override
        public void responseContentPreview(@Nullable String responseContentPreview) {}

        @Override
        public void responseTrailers(HttpHeaders responseTrailers) {}

        @Override
        public void responseCause(Throwable cause) {}

        @Override
        public void endResponse() {}

        @Override
        public void endResponse(Throwable responseCause) {}

        @Override
        public void endResponse(long responseEndTimeNanos) {}

        @Override
        public void endResponse(Throwable responseCause, long responseEndTimeNanos) {}

        @Override
        public boolean isDeferred(RequestLogProperty property) {
            return false;
        }

        @Override
        public boolean isDeferred(RequestLogProperty... properties) {
            return false;
        }

        @Override
        public boolean isDeferred(Iterable<RequestLogProperty> properties) {
            return false;
        }

        @Override
        public void defer(RequestLogProperty property) {}

        @Override
        public void defer(RequestLogProperty... properties) {}

        @Override
        public void defer(Iterable<RequestLogProperty> properties) {}

        @Override
        public void addChild(RequestLogAccess child) {}

        @Override
        public void endResponseWithLastChild() {}
    }

    private static class NoopHttpRequest implements HttpRequest {

        @Nullable
        @Override
        public String authority() {
            return null;
        }

        @Nullable
        @Override
        public RequestHeaders headers() {
            return null;
        }

        @Nullable
        @Override
        public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
            return null;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long demand() {
            return 0;
        }

        @Nullable
        @Override
        public CompletableFuture<Void> whenComplete() {
            return null;
        }

        @Override
        public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                              SubscriptionOption... options) {}

        @Override
        public void abort() {}

        @Override
        public void abort(Throwable cause) {}
    }
}
