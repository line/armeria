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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

final class EmptyContentDecodedHttpRequest implements DecodedHttpRequest {

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final RequestHeaders headers;
    private final boolean keepAlive;
    private final RoutingContext routingContext;
    private final ExchangeType exchangeType;
    private final long requestStartTimeNanos;
    private final long requestStartTimeMicros;
    @Nullable
    private ServiceRequestContext ctx;

    @Nullable
    private CompletableFuture<AggregatedHttpRequest> aggregateFuture;

    @Nullable
    private HttpResponse response;
    @Nullable
    private Throwable abortResponseCause;

    EmptyContentDecodedHttpRequest(EventLoop eventLoop, int id, int streamId, RequestHeaders headers,
                                   boolean keepAlive, RoutingContext routingContext,
                                   ExchangeType exchangeType, long requestStartTimeNanos,
                                   long requestStartTimeMicros) {
        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.headers = headers;
        this.keepAlive = keepAlive;
        this.routingContext = routingContext;
        this.exchangeType = exchangeType;
        this.requestStartTimeNanos = requestStartTimeNanos;
        this.requestStartTimeMicros = requestStartTimeMicros;
    }

    @Override
    public void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public RoutingContext routingContext() {
        return routingContext;
    }

    @Override
    public Routed<ServiceConfig> route() {
        if (routingContext.hasResult()) {
            return routingContext.result();
        }
        return null;
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
        return false;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public long demand() {
        return 0;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        if (executor.inEventLoop()) {
            subscribe0(subscriber);
        } else {
            executor.execute(() -> subscribe0(subscriber));
        }
    }

    private void subscribe0(Subscriber<? super HttpObject> subscriber) {
        subscriber.onSubscribe(NoopSubscription.get());
        subscriber.onComplete();
        completionFuture.complete(null);
    }

    @Override
    public EventLoop defaultSubscriberExecutor() {
        return eventLoop;
    }

    @Override
    public void abort() {
        completionFuture.complete(null);
    }

    @Override
    public void abort(Throwable cause) {
        completionFuture.complete(null);
    }

    @Override
    public CompletableFuture<List<HttpObject>> collect(EventExecutor executor, SubscriptionOption... options) {
        completionFuture.complete(null);
        return UnmodifiableFuture.completedFuture(ImmutableList.of());
    }

    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        if (aggregateFuture != null) {
            return aggregateFuture;
        }
        completionFuture.complete(null);
        aggregateFuture = UnmodifiableFuture.completedFuture(AggregatedHttpRequest.of(headers));
        return aggregateFuture;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public void close() {}

    @Override
    public void close(Throwable cause) {}

    @Override
    public boolean isClosedSuccessfully() {
        return true;
    }

    @Override
    public void setResponse(HttpResponse response) {
        // TODO(ikhoon): Dedup
        if (abortResponseCause != null) {
            // This means that we already tried to close the request, so abort the response immediately.
            if (!response.isComplete()) {
                response.abort(abortResponseCause);
            }
        } else {
            this.response = response;
        }
    }

    @Override
    public void abortResponse(Throwable cause, boolean cancel) {
        if (abortResponseCause != null) {
            return;
        }
        abortResponseCause = cause;

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
    public boolean isResponseAborted() {
        return abortResponseCause != null;
    }

    @Override
    public ExchangeType exchangeType() {
        return exchangeType;
    }

    @Override
    public long requestStartTimeNanos() {
        return requestStartTimeNanos;
    }

    @Override
    public long requestStartTimeMicros() {
        return requestStartTimeMicros;
    }
}
