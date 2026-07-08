/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.ReproducibleHttpRequestDuplicator;
import com.linecorp.armeria.internal.common.stream.NonOverridableStreamMessageWrapper;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpRequest} whose body can be reproduced on demand, so {@code RetryingClient} and
 * {@code RedirectingClient} can resend it without buffering the whole body in memory.
 *
 * <p>See {@link HttpRequest#reproducible(RequestHeaders, Supplier)} for details and usage.
 *
 * <p>The body factory is invoked lazily: when this request is duplicated (the retry/redirect path),
 * {@link #toDuplicator(EventExecutor, long)} returns a {@link ReproducibleHttpRequestDuplicator} that
 * calls the factory once per attempt and this request's own delegate is never subscribed. When this
 * request is subscribed directly (no retry/redirect decorator), the factory is invoked exactly once,
 * on subscription, to produce the single body. Either way the factory is never called eagerly.
 */
final class ReproducibleHttpRequest
        extends NonOverridableStreamMessageWrapper<HttpObject, HttpRequestDuplicator> implements HttpRequest {

    private final RequestHeaders headers;
    private final Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory;

    ReproducibleHttpRequest(RequestHeaders headers,
                            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory) {
        super(lazyBody(bodyFactory));
        this.headers = headers;
        this.bodyFactory = bodyFactory;
    }

    /**
     * Returns a {@link StreamMessage} that invokes {@code bodyFactory} on its first (and only)
     * subscription, so the factory is not called until this request is actually consumed directly.
     */
    private static StreamMessage<HttpObject> lazyBody(
            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory) {
        return StreamMessage.of((Publisher<HttpObject>) subscriber -> {
            final StreamMessage<? extends HttpObject> body;
            try {
                body = bodyFactory.get();
            } catch (Throwable t) {
                StreamMessage.<HttpObject>aborted(t).subscribe(subscriber);
                return;
            }
            if (body == null) {
                StreamMessage.<HttpObject>aborted(
                        new NullPointerException("bodyFactory.get() returned null.")).subscribe(subscriber);
                return;
            }
            @SuppressWarnings("unchecked")
            final StreamMessage<HttpObject> cast = (StreamMessage<HttpObject>) body;
            cast.subscribe(subscriber);
        });
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @SuppressWarnings("unchecked")
    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        return super.aggregate(options);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor) {
        // Ignore the executor: the reproducible duplicator never buffers, so it needs no subscriber
        // executor. Every duplicate() obtains a fresh body from the factory.
        return new ReproducibleHttpRequestDuplicator(headers, bodyFactory);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor, long maxRequestLength) {
        // maxRequestLength does not apply: this duplicator accumulates nothing, so there is no
        // buffered length to cap. Each attempt streams a fresh body straight from the factory.
        return new ReproducibleHttpRequestDuplicator(headers, bodyFactory);
    }
}
