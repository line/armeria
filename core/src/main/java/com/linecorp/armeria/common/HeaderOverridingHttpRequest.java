/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpRequest} that overrides the {@link RequestHeaders}.
 */
final class HeaderOverridingHttpRequest implements HttpRequest {

    private final HttpRequest delegate;
    private final RequestHeaders headers;

    HeaderOverridingHttpRequest(HttpRequest delegate, RequestHeaders headers) {
        this.delegate = delegate;
        this.headers = headers;
    }

    @Override
    public HttpRequest withHeaders(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        if (headers == newHeaders) {
            return this;
        }

        if (delegate.headers() == newHeaders) {
            return delegate;
        }

        return delegate.withHeaders(newHeaders);
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
    public boolean isComplete() {
        return delegate.isComplete();
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return delegate.completionFuture();
    }

    @Override
    public CompletableFuture<Void> closeFuture() {
        return delegate.closeFuture();
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber) {
        delegate.subscribe(subscriber);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, boolean withPooledObjects) {
        if (withPooledObjects) {
            delegate.subscribe(subscriber, WITH_POOLED_OBJECTS);
        } else {
            delegate.subscribe(subscriber);
        }
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, SubscriptionOption... options) {
        delegate.subscribe(subscriber, options);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor) {
        delegate.subscribe(subscriber, executor);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          boolean withPooledObjects) {
        if (withPooledObjects) {
            delegate.subscribe(subscriber, executor, WITH_POOLED_OBJECTS);
        } else {
            delegate.subscribe(subscriber, executor);
        }
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(subscriber, executor, options);
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll() {
        return delegate.drainAll();
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(boolean withPooledObjects) {
        if (withPooledObjects) {
            return drainAll(WITH_POOLED_OBJECTS);
        } else {
            return drainAll();
        }
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(SubscriptionOption... options) {
        return delegate.drainAll(options);
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(EventExecutor executor) {
        return delegate.drainAll(executor);
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(EventExecutor executor, boolean withPooledObjects) {
        if (withPooledObjects) {
            return drainAll(executor, WITH_POOLED_OBJECTS);
        } else {
            return drainAll(executor);
        }
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(EventExecutor executor, SubscriptionOption... options) {
        return delegate.drainAll(executor, options);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(requireNonNull(cause, "cause"));
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public URI uri() {
        return headers.uri();
    }

    @Override
    @Nullable
    public String scheme() {
        return headers.scheme();
    }

    @Override
    public HttpMethod method() {
        return headers.method();
    }

    @Override
    public String path() {
        return headers.path();
    }

    @Override
    @Nullable
    public String authority() {
        return headers.authority();
    }

    @Override
    @Nullable
    public MediaType contentType() {
        return headers.contentType();
    }

    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate() {
        return delegate.aggregate().thenApply(this::replaceHeaders);
    }

    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(EventExecutor executor) {
        return delegate.aggregate(executor).thenApply(this::replaceHeaders);
    }

    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        return delegate.aggregateWithPooledObjects(alloc).thenApply(this::replaceHeaders);
    }

    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        return delegate.aggregateWithPooledObjects(executor, alloc).thenApply(this::replaceHeaders);
    }

    private AggregatedHttpRequest replaceHeaders(AggregatedHttpRequest req) {
        return AggregatedHttpRequest.of(headers, req.content(), req.trailers());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .addValue(headers()).toString();
    }
}
