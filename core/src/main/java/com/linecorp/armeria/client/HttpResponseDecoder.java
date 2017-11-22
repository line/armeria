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
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.ScheduledFuture;

abstract class HttpResponseDecoder {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseDecoder.class);

    private final IntObjectMap<HttpResponseWrapper> responses = new IntObjectHashMap<>();
    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;
    private boolean disconnectWhenFinished;

    HttpResponseDecoder(Channel channel) {
        this.channel = channel;
        inboundTrafficController = new InboundTrafficController(channel);
    }

    final Channel channel() {
        return channel;
    }

    final InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    HttpResponseWrapper addResponse(
            int id, HttpRequest req, DecodedHttpResponse res, RequestLogBuilder logBuilder,
            long responseTimeoutMillis, long maxContentLength) {

        final HttpResponseWrapper newRes =
                new HttpResponseWrapper(req, res, logBuilder, responseTimeoutMillis, maxContentLength);
        final HttpResponseWriter oldRes = responses.put(id, newRes);

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

    final boolean needsToDisconnect() {
        return disconnectWhenFinished && !hasUnfinishedResponses();
    }

    static final class HttpResponseWrapper implements HttpResponseWriter, Runnable {
        private final HttpRequest request;
        private final DecodedHttpResponse delegate;
        private final RequestLogBuilder logBuilder;
        private final long responseTimeoutMillis;
        private final long maxContentLength;
        private ScheduledFuture<?> responseTimeoutFuture;

        HttpResponseWrapper(HttpRequest request, DecodedHttpResponse delegate, RequestLogBuilder logBuilder,
                            long responseTimeoutMillis, long maxContentLength) {
            this.request = request;
            this.delegate = delegate;
            this.logBuilder = logBuilder;
            this.responseTimeoutMillis = responseTimeoutMillis;
            this.maxContentLength = maxContentLength;
        }

        CompletableFuture<Void> completionFuture() {
            return delegate.completionFuture();
        }

        void scheduleTimeout(ChannelHandlerContext ctx) {
            if (responseTimeoutFuture != null || responseTimeoutMillis <= 0 || !isOpen()) {
                // No need to schedule a response timeout if:
                // - the timeout has been scheduled already,
                // - the timeout has been disabled or
                // - the response stream has been closed already.
                return;
            }

            responseTimeoutFuture = ctx.channel().eventLoop().schedule(
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

        @Override
        public void run() {
            if (request != null) {
                request.abort();
            }

            final ResponseTimeoutException cause = ResponseTimeoutException.get();
            delegate.close(cause);
            logBuilder.endResponse(cause);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean write(HttpObject o) {
            if (o instanceof HttpHeaders) {
                // NB: It's safe to call logBuilder.start() multiple times.
                //     See AbstractMessageLog.start() for more information.
                logBuilder.startResponse();
                final HttpHeaders headers = (HttpHeaders) o;
                final HttpStatus status = headers.status();
                if (status != null && status.codeClass() != HttpStatusClass.INFORMATIONAL) {
                    logBuilder.responseHeaders(headers);
                }
            } else if (o instanceof HttpData) {
                logBuilder.increaseResponseLength(((HttpData) o).length());
            }
            return delegate.write(o);
        }

        @Override
        public boolean write(Supplier<? extends HttpObject> o) {
            return delegate.write(o);
        }

        @Override
        public CompletableFuture<Void> onDemand(Runnable task) {
            return delegate.onDemand(task);
        }

        @Override
        public void close() {
            if (request != null) {
                request.abort();
            }

            if (cancelTimeout()) {
                delegate.close();
                logBuilder.endResponse();
            }
        }

        @Override
        public void close(Throwable cause) {
            if (request != null) {
                request.abort();
            }

            if (cancelTimeout()) {
                delegate.close(cause);
                logBuilder.endResponse(cause);
            } else {
                if (!Exceptions.isExpected(cause)) {
                    logger.warn("Unexpected exception:", cause);
                }
            }
        }

        @Override
        public void respond(HttpStatus status) {
            delegate.respond(status);
        }

        @Override
        public void respond(HttpStatus status,
                            MediaType mediaType, String content) {
            delegate.respond(status, mediaType, content);
        }

        @Override
        public void respond(HttpStatus status,
                            MediaType mediaType, String format, Object... args) {
            delegate.respond(status, mediaType, format, args);
        }

        @Override
        public void respond(HttpStatus status,
                            MediaType mediaType, byte[] content) {
            delegate.respond(status, mediaType, content);
        }

        @Override
        public void respond(HttpStatus status,
                            MediaType mediaType, byte[] content, int offset, int length) {
            delegate.respond(status, mediaType, content, offset, length);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
