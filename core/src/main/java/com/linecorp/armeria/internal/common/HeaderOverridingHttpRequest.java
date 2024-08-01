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
package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpRequest} that overrides the {@link RequestHeaders}.
 */
public final class HeaderOverridingHttpRequest implements HttpRequest {

    private final HttpRequest delegate;
    private final RequestHeaders headers;

    public static HeaderOverridingHttpRequest of(HttpRequest delegate, RequestHeaders headers) {
        requireNonNull(delegate, "delegate");
        requireNonNull(headers, "headers");
        if (delegate instanceof HeaderOverridingHttpRequest) {
            return new HeaderOverridingHttpRequest(
                    ((HeaderOverridingHttpRequest) delegate).delegate(), headers);
        }
        return new HeaderOverridingHttpRequest(delegate, headers);
    }

    HeaderOverridingHttpRequest(HttpRequest delegate, RequestHeaders headers) {
        this.delegate = delegate;
        this.headers = headers;
    }

    public HttpRequest delegate() {
        return delegate;
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
    public long demand() {
        return delegate.demand();
    }

    @Override
    public boolean isComplete() {
        return delegate.isComplete();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public CompletableFuture<List<HttpObject>> collect(EventExecutor executor, SubscriptionOption... options) {
        return delegate.collect(executor, options);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(subscriber, executor, options);
    }

    @Override
    public EventExecutor defaultSubscriberExecutor() {
        return delegate.defaultSubscriberExecutor();
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
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        return delegate.aggregate(options).thenApply(this::replaceHeaders);
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
