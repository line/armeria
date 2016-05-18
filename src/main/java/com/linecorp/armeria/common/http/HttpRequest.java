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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.reactivestreams.RichPublisher;

public interface HttpRequest extends Request, RichPublisher<HttpObject> {

    static HttpRequest of(HttpHeaders headers, Publisher<? extends HttpObject> publisher) {
        return new PublisherBasedHttpRequest(headers, true, publisher);
    }

    HttpHeaders headers();

    boolean isKeepAlive();

    default String scheme() {
        return headers().scheme();
    }

    default HttpRequest scheme(String scheme) {
        headers().scheme(scheme);
        return this;
    }

    default HttpMethod method() {
        return headers().method();
    }

    default HttpRequest method(HttpMethod method) {
        headers().method(method);
        return this;
    }

    default String path() {
        return headers().path();
    }

    default HttpRequest path(String path) {
        headers().path(path);
        return this;
    }

    default String authority() {
        return headers().authority();
    }

    default HttpRequest authority(String authority) {
        headers().authority(authority);
        return this;
    }

    /**
     * Aggregates the request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate() {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        subscribe(new HttpRequestAggregator(this, future));
        return future;
    }

    /**
     * Aggregates the request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(Executor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        subscribe(new HttpRequestAggregator(this, future), executor);
        return future;
    }
}
