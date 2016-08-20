/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.http;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * A streamed HTTP/2 {@link Request}.
 *
 * <p>Note: The initial {@link HttpHeaders} is not signaled to {@link Subscriber}s. It is readily available
 * via {@link #headers()}.
 */
public interface HttpRequest extends Request, StreamMessage<HttpObject> {

    /**
     * Creates a new instance from an existing {@link HttpHeaders} and {@link Publisher}.
     */
    static HttpRequest of(HttpHeaders headers, Publisher<? extends HttpObject> publisher) {
        return new PublisherBasedHttpRequest(headers, true, publisher);
    }

    /**
     * Returns a new {@link HttpRequest} with empty content.
     */
    static HttpRequest of(HttpHeaders headers) {
        // TODO(trustin): Use no-op Queue implementation for QueueBasedPublisher?
        final DefaultHttpRequest req = new DefaultHttpRequest(headers);
        req.close();
        return req;
    }

    /**
     * Returns the initial HTTP/2 headers of this request.
     */
    HttpHeaders headers();

    /**
     * Returns whether to keep the connection alive after this request is handled.
     */
    boolean isKeepAlive();

    /**
     * Returns the scheme of this request. This method is a shortcut of {@code headers().scheme()}.
     */
    default String scheme() {
        return headers().scheme();
    }

    /**
     * Sets the scheme of this request. This method is a shortcut of {@code headers().scheme(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest scheme(String scheme) {
        headers().scheme(scheme);
        return this;
    }

    /**
     * Returns the method of this request. This method is a shortcut of {@code headers().method()}.
     */
    default HttpMethod method() {
        return headers().method();
    }

    /**
     * Sets the method of this request. This method is a shortcut of {@code headers().method(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest method(HttpMethod method) {
        headers().method(method);
        return this;
    }

    /**
     * Returns the path of this request. This method is a shortcut of {@code headers().path()}.
     */
    default String path() {
        return headers().path();
    }

    /**
     * Sets the path of this request. This method is a shortcut of {@code headers().path(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest path(String path) {
        headers().path(path);
        return this;
    }

    /**
     * Returns the authority of this request. This method is a shortcut of {@code headers().authority()}.
     */
    default String authority() {
        return headers().authority();
    }

    /**
     * Sets the authority of this request. This method is a shortcut of {@code headers().authority(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest authority(String authority) {
        headers().authority(authority);
        return this;
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate() {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        subscribe(new HttpRequestAggregator(this, future));
        return future;
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(Executor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        subscribe(new HttpRequestAggregator(this, future), executor);
        return future;
    }
}
