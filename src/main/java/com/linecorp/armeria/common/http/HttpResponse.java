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

import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * A streamed HTTP/2 {@link Response}.
 */
public interface HttpResponse extends Response, StreamMessage<HttpObject> {

    /**
     * Creates a new instance from an existing {@link Publisher}.
     */
    static HttpResponse of(Publisher<? extends HttpObject> publisher) {
        return new PublisherBasedHttpResponse(publisher);
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate() {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        subscribe(new HttpResponseAggregator(future));
        return future;
    }

    @Override
    CompletableFuture<Void> closeFuture();

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(Executor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        subscribe(new HttpResponseAggregator(future), executor);
        return future;
    }
}
