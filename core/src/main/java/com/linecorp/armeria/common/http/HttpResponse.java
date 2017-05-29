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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
     * Creates a new failed instance.
     */
    static HttpResponse ofFailed(Throwable cause) {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.close(cause);
        return res;
    }

    /**
     * Creates a new instance that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned response will be
     * closed with the same cause as well.
     */
    static HttpResponse from(CompletionStage<? extends HttpResponse> stage) {
        requireNonNull(stage, "stage");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        stage.whenComplete((delegate, cause) -> {
            if (cause != null) {
                res.close(cause);
            } else if (delegate == null) {
                res.close(new NullPointerException("delegate stage produced a null response: " + stage));
            } else {
                res.delegate(delegate);
            }
        });
        return res;
    }

    @Override
    CompletableFuture<Void> closeFuture();

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate() {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future);
        closeFuture().whenComplete(aggregator);
        subscribe(aggregator);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(Executor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future);
        closeFuture().whenCompleteAsync(aggregator, executor);
        subscribe(aggregator, executor);
        return future;
    }
}
