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

import static java.util.Objects.requireNonNull;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

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

    private static final long DEFAULT_TIMEOUT_MILLIS = 10000L;

    private final AtomicInteger numActiveRequests = new AtomicInteger();
    private final Queue<PendingTask> pendingRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrencyLimit concurrencyLimit;

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}, with the default timeout of {@value #DEFAULT_TIMEOUT_MILLIS}
     * milliseconds.
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
        this(delegate, new ConcurrencyLimit.Builder()
                .maxConcurrency(maxConcurrency)
                .timeout(timeout, unit)
                .build());
    }

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}.
     *
     * @param delegate the delegate {@link Client}
     * @param concurrencyLimit the concurrency limit config
     */
    public AbstractConcurrencyLimitingClient(Client<I, O> delegate, ConcurrencyLimit concurrencyLimit) {
        super(delegate);
        this.concurrencyLimit = concurrencyLimit;
    }

    static void validateAll(int maxConcurrency, long timeout, TimeUnit unit) {
        validateMaxConcurrency(maxConcurrency);
        validateTimeout(timeout);
        requireNonNull(unit, "unit");
    }

    static long validateTimeout(long timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout: " + timeout + " (expected: >= 0)");
        }
        return timeout;
    }

    static int validateMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency < 0) {
            throw new IllegalArgumentException("maxConcurrency: " + maxConcurrency + " (expected: >= 0)");
        }
        return maxConcurrency;
    }

    /**
     * Returns the number of the {@link Request}s that are being executed.
     */
    public final int numActiveRequests() {
        return numActiveRequests.get();
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        return concurrencyLimit.shouldLimit(ctx) ? limitedExecute(ctx, req)
                                                 : unlimitedExecute(ctx, req);
    }

    private O limitedExecute(ClientRequestContext ctx, I req) throws Exception {
        final CompletableFuture<O> resFuture = new CompletableFuture<>();
        final O deferred = newDeferredResponse(ctx, resFuture);
        final PendingTask currentTask = new PendingTask(ctx, req, resFuture);

        pendingRequests.add(currentTask);
        drain();

        final long timeoutMillis = concurrencyLimit.timeoutMillis();
        if (!currentTask.isRun() && timeoutMillis != 0) {
            // Current request was not delegated. Schedule a timeout.
            final ScheduledFuture<?> timeoutFuture = ctx.eventLoop().withoutContext().schedule(
                    () -> resFuture.completeExceptionally(
                            UnprocessedRequestException.of(RequestTimeoutException.get())),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            currentTask.set(timeoutFuture);
        }

        return deferred;
    }

    private O unlimitedExecute(ClientRequestContext ctx, I req) throws Exception {
        numActiveRequests.incrementAndGet();
        boolean success = false;
        try {
            final O res = unwrap().execute(ctx, req);
            res.whenComplete().handle((unused, cause) -> {
                numActiveRequests.decrementAndGet();
                return null;
            });
            success = true;
            return res;
        } finally {
            if (!success) {
                numActiveRequests.decrementAndGet();
            }
        }
    }

    final void drain() {
        final int maxConcurrency = concurrencyLimit.maxConcurrency();
        while (!pendingRequests.isEmpty()) {
            final int currentActiveRequests = numActiveRequests.get();
            if (currentActiveRequests >= maxConcurrency) {
                break;
            }

            if (numActiveRequests.compareAndSet(currentActiveRequests, currentActiveRequests + 1)) {
                final PendingTask task = pendingRequests.poll();
                if (task == null) {
                    numActiveRequests.decrementAndGet();
                    if (!pendingRequests.isEmpty()) {
                        // Another request might have been added to the queue while numActiveRequests reached
                        // at its limit.
                        continue;
                    } else {
                        break;
                    }
                }

                task.run();
            }
        }
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

    ConcurrencyLimit concurrencyLimit() {
        return concurrencyLimit;
    }

    private final class PendingTask extends AtomicReference<ScheduledFuture<?>> implements Runnable {

        private static final long serialVersionUID = -7092037489640350376L;

        private final ClientRequestContext ctx;
        private final I req;
        private final CompletableFuture<O> resFuture;
        private boolean isRun;

        PendingTask(ClientRequestContext ctx, I req, CompletableFuture<O> resFuture) {
            this.ctx = ctx;
            this.req = req;
            this.resFuture = resFuture;
        }

        boolean isRun() {
            return isRun;
        }

        @Override
        public void run() {
            isRun = true;

            final ScheduledFuture<?> timeoutFuture = get();
            if (timeoutFuture != null) {
                if (timeoutFuture.isDone() || !timeoutFuture.cancel(false)) {
                    // Timeout task ran already or is determined to run.
                    numActiveRequests.decrementAndGet();
                    return;
                }
            }

            try (SafeCloseable ignored = ctx.replace()) {
                try {
                    final O actualRes = unwrap().execute(ctx, req);
                    actualRes.whenComplete().handleAsync((unused, cause) -> {
                        numActiveRequests.decrementAndGet();
                        drain();
                        return null;
                    }, ctx.eventLoop().withoutContext());
                    resFuture.complete(actualRes);
                } catch (Throwable t) {
                    numActiveRequests.decrementAndGet();
                    resFuture.completeExceptionally(t);
                }
            }
        }
    }
}
