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

import static java.util.Objects.requireNonNull;

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
 * <p>The body factory is invoked lazily: it is never called during construction. On the
 * retry/redirect path {@link #toDuplicator(EventExecutor, long)} returns a
 * {@link ReproducibleHttpRequestDuplicator} that calls the factory once per attempt, and this
 * request's own delegate is never subscribed. When this request is instead consumed directly (no
 * retry/redirect decorator), the factory is invoked once when the delegate is first subscribed to
 * produce the single body.
 *
 * <p>"First subscription" here includes {@link #abort()}/{@link #abort(Throwable)} called before any
 * real subscriber arrives: the underlying {@link StreamMessage} subscribes an aborting subscriber,
 * which runs the factory once so the produced body can be aborted and released. This means aborting a
 * directly-consumed request that was never sent still invokes the factory exactly once (it does not
 * regenerate on a subsequent subscribe, because a stream permits only one subscription). The factory
 * therefore runs at most once on the direct path.
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
            // This single-arg subscribe forwards neither the caller's SubscriptionOptions
            // (WITH_POOLED_OBJECTS / NOTIFY_CANCELLATION) nor the requested executor to the produced
            // body. That is acceptable only because this is a cold path: a reproducible request is
            // meant to be driven by a retry/redirect decorator, which uses toDuplicator(...) and never
            // subscribes this delegate. This branch is reached only when a reproducible request is
            // consumed directly, with no such decorator.
            cast.subscribe(subscriber);
        });
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public HttpRequest withHeaders(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        if (headers == newHeaders) {
            return this;
        }
        // Preserve reproducibility across a header rewrite (e.g. a base-URI path prefix applied by
        // DefaultWebClient, or a redirect/retry decorator overriding the path). The default
        // HttpRequest.withHeaders wraps this in a HeaderOverridingHttpRequest, which does not override
        // toDuplicator and would therefore fall back to the buffering DefaultHttpRequestDuplicator,
        // reintroducing the ~2 GiB size limit this request type exists to avoid. Rebinding the same
        // factory to the new headers keeps the non-buffering toDuplicator path.
        return new ReproducibleHttpRequest(newHeaders, bodyFactory);
    }

    @SuppressWarnings("unchecked")
    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        return super.aggregate(options);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor) {
        return toDuplicator(executor, 0);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor, long maxRequestLength) {
        // Neither argument applies: the reproducible duplicator never buffers, so it needs no
        // subscriber executor and has no accumulated length to cap. Each attempt streams a fresh
        // body straight from the factory.
        return new ReproducibleHttpRequestDuplicator(headers, bodyFactory);
    }
}
