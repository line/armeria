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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.SafeCloseable;

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
public abstract class ConcurrencyLimitingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private static final long DEFAULT_TIMEOUT_MILLIS = 10000L;

    private final int maxConcurrency;
    private final long timeoutMillis;
    private final AtomicInteger numActiveRequests = new AtomicInteger();
    private final Queue<PendingTask> pendingRequests = new ConcurrentLinkedQueue<>();

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}, with the default timeout of {@value #DEFAULT_TIMEOUT_MILLIS}
     * milliseconds.
     *
     * @param delegate the delegate {@link Client}
     * @param maxConcurrency the maximum number of concurrent active requests. {@code 0} to disable the limit.
     */
    protected ConcurrencyLimitingClient(Client<I, O> delegate, int maxConcurrency) {
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
    protected ConcurrencyLimitingClient(Client<I, O> delegate,
                                        int maxConcurrency, long timeout, TimeUnit unit) {
        super(delegate);

        validateAll(maxConcurrency, timeout, unit);

        this.maxConcurrency = maxConcurrency;
        timeoutMillis = unit.toMillis(timeout);
    }

    static void validateAll(int maxConcurrency, long timeout, TimeUnit unit) {
        validateMaxConcurrency(maxConcurrency);
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout: " + timeout + " (expected: >= 0)");
        }
        requireNonNull(unit, "unit");
    }

    static void validateMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency < 0) {
            throw new IllegalArgumentException("maxConcurrency: " + maxConcurrency + " (expected: >= 0)");
        }
    }

    /**
     * Returns the number of the {@link Request}s that are being executed.
     */
    public int numActiveRequests() {
        return numActiveRequests.get();
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        return maxConcurrency == 0 ? unlimitedExecute(ctx, req)
                                   : limitedExecute(ctx, req);
    }

    private O limitedExecute(ClientRequestContext ctx, I req) throws Exception {
        final Deferred<O> deferred = defer(ctx, req);
        final PendingTask currentTask = new PendingTask(ctx, req, deferred);

        pendingRequests.add(currentTask);
        drain();

        if (!currentTask.isRun() && timeoutMillis != 0) {
            // Current request was not delegated. Schedule a timeout.
            final ScheduledFuture<?> timeoutFuture = ctx.eventLoop().schedule(
                    () -> deferred
                            .close(new UnprocessedRequestException(PendingRequestTimeoutException.get())),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            currentTask.set(timeoutFuture);
        }

        return deferred.response();
    }

    private O unlimitedExecute(ClientRequestContext ctx, I req) throws Exception {
        numActiveRequests.incrementAndGet();
        boolean success = false;
        try {
            final O res = delegate().execute(ctx, req);
            res.completionFuture().handle((unused, cause) -> {
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

    void drain() {
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
     * Defers the specified {@link Request}.
     *
     * @return a new {@link Deferred} which provides the interface for updating the result of
     *         {@link Request} execution later.
     */
    protected abstract Deferred<O> defer(ClientRequestContext ctx, I req) throws Exception;

    /**
     * Provides the interface for updating the result of a {@link Request} execution when its {@link Response}
     * is ready.
     *
     * @param <O> the {@link Response} type
     */
    public interface Deferred<O extends Response> {
        /**
         * Returns the {@link Response} which will delegate to the {@link Response} set by
         * {@link #delegate(Response)}.
         */
        O response();

        /**
         * Delegates the {@link #response() response} to the specified {@link Response}.
         */
        void delegate(O response);

        /**
         * Closes the {@link #response()} without delegating.
         */
        void close(Throwable cause);
    }

    private final class PendingTask extends AtomicReference<ScheduledFuture<?>> implements Runnable {

        private static final long serialVersionUID = -7092037489640350376L;

        private final ClientRequestContext ctx;
        private final I req;
        private final Deferred<O> deferred;
        private boolean isRun;

        PendingTask(ClientRequestContext ctx, I req, Deferred<O> deferred) {
            this.ctx = ctx;
            this.req = req;
            this.deferred = deferred;
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

            try (SafeCloseable ignored = ctx.push()) {
                try {
                    final O actualRes = delegate().execute(ctx, req);
                    actualRes.completionFuture().handleAsync((unused, cause) -> {
                        numActiveRequests.decrementAndGet();
                        drain();
                        return null;
                    }, ctx.eventLoop());
                    deferred.delegate(actualRes);
                } catch (Throwable t) {
                    numActiveRequests.decrementAndGet();
                    deferred.close(t);
                }
            }
        }
    }
}
