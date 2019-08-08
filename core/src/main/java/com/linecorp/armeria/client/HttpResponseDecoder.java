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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.ScheduledFuture;

abstract class HttpResponseDecoder {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseDecoder.class);

    private final IntObjectMap<HttpResponseWrapper> responses = new IntObjectHashMap<>();
    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;
    private boolean disconnectWhenFinished;

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
            int id, @Nullable HttpRequest req, DecodedHttpResponse res, RequestLogBuilder logBuilder,
            long responseTimeoutMillis, long maxContentLength) {

        final HttpResponseWrapper newRes =
                new HttpResponseWrapper(req, res, logBuilder, responseTimeoutMillis, maxContentLength);
        final HttpResponseWrapper oldRes = responses.put(id, newRes);

        assert oldRes == null : "addResponse(" + id + ", " + res + ", " + responseTimeoutMillis + "): " +
                                oldRes;

        return newRes;
    }

    @Nullable
    final HttpResponseWrapper getResponse(int id) {
        return responses.get(id);
    }

    @Nullable
    final HttpResponseWrapper getResponse(int id, boolean remove) {
        return remove ? removeResponse(id) : getResponse(id);
    }

    @Nullable
    final HttpResponseWrapper removeResponse(int id) {
        return responses.remove(id);
    }

    final int unfinishedResponses() {
        return responses.size();
    }

    final boolean hasUnfinishedResponses() {
        return !responses.isEmpty();
    }

    final void failUnfinishedResponses(Throwable cause) {
        try {
            for (HttpResponseWrapper res : responses.values()) {
                res.close(cause);
            }
        } finally {
            responses.clear();
        }
    }

    final void disconnectWhenFinished() {
        disconnectWhenFinished = true;
    }

    final boolean needsToDisconnectNow() {
        return disconnectWhenFinished && !hasUnfinishedResponses();
    }

    final boolean needsToDisconnectWhenFinished() {
        return disconnectWhenFinished;
    }

    static final class HttpResponseWrapper implements StreamWriter<HttpObject>, Runnable {

        enum State {
            WAIT_NON_INFORMATIONAL,
            WAIT_DATA_OR_TRAILERS,
            DONE
        }

        @Nullable
        private final HttpRequest request;
        private final DecodedHttpResponse delegate;
        private final RequestLogBuilder logBuilder;
        private final long responseTimeoutMillis;
        private final long maxContentLength;
        @Nullable
        private ScheduledFuture<?> responseTimeoutFuture;

        private boolean loggedResponseFirstBytesTransferred;

        private State state = State.WAIT_NON_INFORMATIONAL;

        HttpResponseWrapper(@Nullable HttpRequest request, DecodedHttpResponse delegate,
                            RequestLogBuilder logBuilder, long responseTimeoutMillis, long maxContentLength) {
            this.request = request;
            this.delegate = delegate;
            this.logBuilder = logBuilder;
            this.responseTimeoutMillis = responseTimeoutMillis;
            this.maxContentLength = maxContentLength;
        }

        CompletableFuture<Void> completionFuture() {
            return delegate.completionFuture();
        }

        void scheduleTimeout(EventLoop eventLoop) {
            if (responseTimeoutFuture != null || responseTimeoutMillis <= 0 || !isOpen()) {
                // No need to schedule a response timeout if:
                // - the timeout has been scheduled already,
                // - the timeout has been disabled or
                // - the response stream has been closed already.
                return;
            }

            responseTimeoutFuture = eventLoop.schedule(
                    this, responseTimeoutMillis, TimeUnit.MILLISECONDS);
        }

        boolean cancelTimeout() {
            final ScheduledFuture<?> responseTimeoutFuture = this.responseTimeoutFuture;
            if (responseTimeoutFuture == null) {
                return true;
            }

            this.responseTimeoutFuture = null;
            return responseTimeoutFuture.cancel(false);
        }

        long maxContentLength() {
            return maxContentLength;
        }

        long writtenBytes() {
            return delegate.writtenBytes();
        }

        void logResponseFirstBytesTransferred() {
            if (!loggedResponseFirstBytesTransferred) {
                logBuilder.responseFirstBytesTransferred();
                loggedResponseFirstBytesTransferred = true;
            }
        }

        @Override
        public void run() {
            final ResponseTimeoutException cause = ResponseTimeoutException.get();
            delegate.close(cause);
            logBuilder.endResponse(cause);

            if (request != null) {
                request.abort();
            }
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /**
         * Writes the specified {@link HttpObject} to {@link DecodedHttpResponse}. This method is only called
         * from {@link Http1ResponseDecoder} and {@link Http2ResponseDecoder}. If this returns {@code false},
         * it means the response stream has been closed due to disconnection or by the response consumer.
         * So the caller do not need to handle such cases because it will be notified to the response
         * consumer anyway.
         */
        @Override
        public boolean tryWrite(HttpObject o) {
            switch (state) {
                case WAIT_NON_INFORMATIONAL:
                    // NB: It's safe to call logBuilder.startResponse() multiple times.
                    logBuilder.startResponse();

                    assert o instanceof HttpHeaders && !(o instanceof RequestHeaders) : o;

                    if (o instanceof ResponseHeaders) {
                        final ResponseHeaders headers = (ResponseHeaders) o;
                        final HttpStatus status = headers.status();
                        if (status.codeClass() != HttpStatusClass.INFORMATIONAL) {
                            state = State.WAIT_DATA_OR_TRAILERS;
                            logBuilder.responseHeaders(headers);
                        }
                    }
                    break;
                case WAIT_DATA_OR_TRAILERS:
                    if (o instanceof HttpHeaders) {
                        state = State.DONE;
                        logBuilder.responseTrailers((HttpHeaders) o);
                    } else {
                        logBuilder.increaseResponseLength((HttpData) o);
                    }
                    break;
                case DONE:
                    ReferenceCountUtil.safeRelease(o);
                    return false;
            }
            return delegate.tryWrite(o);
        }

        @Override
        public boolean tryWrite(Supplier<? extends HttpObject> o) {
            return delegate.tryWrite(o);
        }

        @Override
        public CompletableFuture<Void> onDemand(Runnable task) {
            return delegate.onDemand(task);
        }

        void onSubscriptionCancelled(@Nullable Throwable cause) {
            close(cause, this::cancelAction);
        }

        @Override
        public void close() {
            close(null, this::closeAction);
        }

        @Override
        public void close(Throwable cause) {
            close(cause, this::closeAction);
        }

        private void close(@Nullable Throwable cause,
                           Consumer<Throwable> actionOnTimeoutCancelled) {
            state = State.DONE;
            if (cancelTimeout()) {
                actionOnTimeoutCancelled.accept(cause);
            } else {
                if (cause != null && !Exceptions.isExpected(cause)) {
                    logger.warn("Unexpected exception:", cause);
                }
            }

            if (request != null) {
                request.abort();
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

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
