/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamCallbackListener;
import com.linecorp.armeria.common.stream.StreamMessageAndWriter;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

/**
 * TODO: consider deduping with {@link EmptyContentDecodedHttpRequest}.
 */
final class DefaultDecodedHttpRequest implements DecodedHttpRequestWriter,
                                                 StreamCallbackListener<HttpObject> {

    private final StreamMessageAndWriter<HttpObject> delegate;
    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final boolean keepAlive;
    private final InboundTrafficController inboundTrafficController;
    private final long maxRequestLength;
    private final RequestHeaders headers;
    @Nullable
    private ServiceRequestContext ctx;
    private long transferredBytes;

    @Nullable
    private HttpResponse response;
    private boolean isResponseAborted;

    DefaultDecodedHttpRequest(StreamMessageAndWriter<HttpObject> delegate,
                              EventLoop eventLoop, int id, int streamId, RequestHeaders headers,
                              boolean keepAlive, InboundTrafficController inboundTrafficController,
                              long maxRequestLength) {
        this.delegate = delegate;
        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.keepAlive = keepAlive;
        this.inboundTrafficController = inboundTrafficController;
        this.maxRequestLength = maxRequestLength;
        this.headers = headers;

        delegate.setCallbackListener(this);
    }

    @Override
    public void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public int streamId() {
        return streamId;
    }

    @Override
    public boolean isKeepAlive() {
        return keepAlive;
    }

    @Override
    public long maxRequestLength() {
        return ctx != null ? ctx.maxRequestLength() : maxRequestLength;
    }

    @Override
    public long transferredBytes() {
        return transferredBytes;
    }

    @Override
    public void increaseTransferredBytes(long delta) {
        if (transferredBytes > Long.MAX_VALUE - delta) {
            transferredBytes = Long.MAX_VALUE;
        } else {
            transferredBytes += delta;
        }
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long demand() {
        return delegate.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(subscriber, executor, options);
    }

    @Override
    public EventLoop defaultSubscriberExecutor() {
        return eventLoop;
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }

    @Override
    public boolean tryWrite(HttpObject obj) {
        assert ctx != null : "uninitialized DecodedHttpRequest must be aborted.";

        final boolean published;
        if (obj instanceof HttpHeaders) { // HTTP trailers.
            published = delegate.tryWrite(obj);
            ctx.logBuilder().requestTrailers((HttpHeaders) obj);
            // Close this stream because HTTP trailers is the last element of the request.
            close();
        } else {
            final HttpData httpData = (HttpData) obj;
            httpData.touch(ctx);
            published = delegate.tryWrite(httpData);
            if (published) {
                ctx.logBuilder().increaseRequestLength(httpData);
                inboundTrafficController.inc(httpData.length());
            }
            if (obj.isEndOfStream()) {
                close();
            }
        }

        return published;
    }

    @Override
    public CompletableFuture<Void> whenConsumed() {
        return delegate.whenConsumed();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void close(Throwable cause) {
        delegate.close(cause);
    }

    @Override
    public void onRemoval(HttpObject obj) {
        if (obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            inboundTrafficController.dec(length);
        }
    }

    @Override
    public void setResponse(HttpResponse response) {
        if (isResponseAborted) {
            // This means that we already tried to close the request, so abort the response immediately.
            if (!response.isComplete()) {
                response.abort();
            }
        } else {
            this.response = response;
        }
    }

    @Override
    public void abortResponse(Throwable cause, boolean cancel) {
        isResponseAborted = true;

        // Make sure to invoke the ServiceRequestContext.whenRequestCancelling() and whenRequestCancelled()
        // by cancelling a request
        if (cancel && ctx != null) {
            ctx.cancel(cause);
        }

        // Try to close the request first, then abort the response if it is already closed.
        if (!delegate.tryClose(cause) &&
            response != null && !response.isComplete()) {
            response.abort(cause);
        }
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }
}
