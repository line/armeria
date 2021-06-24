/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.limit;

import static com.linecorp.armeria.client.limit.ConcurrencyLimitBuilder.DEFAULT_TIMEOUT_MILLIS;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.limit.ConcurrencyLimit.Permit;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.EventExecutor;

/**
 * An abstract {@link Client} decorator that limits the concurrent number of active requests.
 *
 * <p>{@link #numActiveRequests()} increases when {@link Client#execute(ClientRequestContext, Request)} is
 * invoked and decreases when the {@link Response} returned by the
 * {@link Client#execute(ClientRequestContext, Request)} is closed. When {@link #numActiveRequests()} reaches
 * at the configured {@code maxConcurrency} the {@link Request}s are deferred until the currently active
 * {@link Request}s are completed.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractConcurrencyLimitingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private final ConcurrencyLimit<ClientRequestContext> concurrencyLimit;
    private final AtomicInteger numActiveRequests = new AtomicInteger();

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}, with the default timeout of
     * {@value ConcurrencyLimitBuilder#DEFAULT_TIMEOUT_MILLIS} milliseconds.
     *
     * @param delegate the delegate {@link Client}
     * @param maxConcurrency the maximum number of concurrent active requests. {@code 0} to disable the limit.
     */
    protected AbstractConcurrencyLimitingClient(Client<I, O> delegate, int maxConcurrency) {
        this(delegate, maxConcurrency, DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}.
     *
     * @param delegate the delegate {@link Client}
     * @param maxConcurrency the maximum number of concurrent active requests. {@code 0} to disable the limit.
     * @param timeout the amount of time until this decorator fails the request if the request was not
     *                delegated to the {@code delegate} before then
     */
    protected AbstractConcurrencyLimitingClient(Client<I, O> delegate,
                                                int maxConcurrency, long timeout, TimeUnit unit) {
        this(delegate, DefaultConcurrencyLimit.builder(maxConcurrency)
                                              .timeoutMillis(unit.toMillis(timeout))
                                              .build());
    }

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}.
     *
     * @param delegate the delegate {@link Client}
     * @param concurrencyLimit the concurrency limit config
     */
    protected AbstractConcurrencyLimitingClient(Client<I, O> delegate,
                                                ConcurrencyLimit concurrencyLimit) {
        super(delegate);
        this.concurrencyLimit = concurrencyLimit;
    }

    /**
     * Returns the number of the {@link Request}s that are being executed.
     */
    public final int numActiveRequests() {
        return numActiveRequests.get();
    }

    private static class Pair<L,R> {
        final L left;
        final R right;

        private Pair(L left, R right) {
            this.left = left;
            this.right = right;
        }
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final CompletableFuture<O> resFuture = new CompletableFuture<>();
        final O deferred = newDeferredResponse(ctx, resFuture);
        concurrencyLimit.acquire(ctx)
                        .whenCompleteAsync((permit, throwable) -> {
                            if (throwable != null) {
                                resFuture.completeExceptionally(throwable);
                                return;
                            }
                            numActiveRequests.incrementAndGet();
                            try (SafeCloseable ignored = ctx.replace()) {
                                try {
                                    final O actualRes = unwrap().execute(ctx, req);
                                    actualRes.whenComplete().whenCompleteAsync((unused, cause) -> {
                                        permit.release();
                                        numActiveRequests.decrementAndGet();
                                    }, ctx.eventLoop().withoutContext());
                                    resFuture.complete(actualRes);

                                } catch (Throwable t) {
                                    permit.release();
                                    numActiveRequests.decrementAndGet();
                                    resFuture.completeExceptionally(t);
                                }
                            }
                        }, ctx.eventLoop().withoutContext());
        return deferred;
    }

    /**
     * Implement this method to return a new {@link Response} which delegates to the {@link Response}
     * the specified {@link CompletionStage} is completed with. For example, you could use
     * {@link HttpResponse#from(CompletionStage, EventExecutor)}:
     * <pre>{@code
     * protected HttpResponse newDeferredResponse(
     *         ClientRequestContext ctx, CompletionStage<HttpResponse> resFuture) {
     *     return HttpResponse.from(resFuture, ctx.eventLoop());
     * }
     * }</pre>
     */
    protected abstract O newDeferredResponse(ClientRequestContext ctx,
                                             CompletionStage<O> resFuture) throws Exception;

    @VisibleForTesting
    ConcurrencyLimit concurrencyLimit() {
        return concurrencyLimit;
    }

}
