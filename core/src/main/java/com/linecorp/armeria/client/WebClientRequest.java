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

package com.linecorp.armeria.client;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;

final class WebClientRequest implements HttpRequest {

    private final HttpRequest delegate;
    private final long responseTimeoutMillis;
    private final Map<AttributeKey<?>, Object> attributes;

    WebClientRequest(HttpRequest delegate,
                     long responseTimeoutMillis,
                     @Nullable Map<AttributeKey<?>, Object> attributes) {
        this.delegate = delegate;
        this.responseTimeoutMillis = responseTimeoutMillis;
        this.attributes = firstNonNull(attributes, ImmutableMap.of());
    }

    /**
     * Returns the response timeout in milliseconds. {@code 0} disables the limit.
     * {@code -1} disables this option and the response timeout of a client is used instead.
     */
    long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    /**
     * Returns the {@link AttributeKey}s and their associated values.
     */
    Map<AttributeKey<?>, Object> attrs() {
        return attributes;
    }

    @Override
    public RequestHeaders headers() {
        return delegate.headers();
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
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }
}
