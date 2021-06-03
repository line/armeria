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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

final class EmptyContentDecodedHttpRequest implements DecodedHttpRequest {

    private final HttpRequest delegate;
    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final boolean keepAlive;
    @Nullable
    private ServiceRequestContext ctx;

    @Nullable
    private HttpResponse response;
    private boolean isResponseAborted;

    EmptyContentDecodedHttpRequest(EventLoop eventLoop, int id, int streamId, RequestHeaders headers,
                                   boolean keepAlive) {
        delegate = HttpRequest.of(headers);
        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.keepAlive = keepAlive;
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
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor) {
        delegate.subscribe(subscriber, executor);
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
    public CompletableFuture<List<HttpObject>> collect(EventExecutor executor, SubscriptionOption... options) {
        return delegate.collect(executor, options);
    }

    @Override
    public RequestHeaders headers() {
        return delegate.headers();
    }

    @Override
    public void close() {}

    @Override
    public void close(Throwable cause) {}

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

        if (response != null && !response.isComplete()) {
            response.abort(cause);
        }
    }

    @Override
    public long maxRequestLength() {
        return 0;
    }

    @Override
    public long transferredBytes() {
        return 0;
    }

    @Override
    public void increaseTransferredBytes(long delta) {}
}
