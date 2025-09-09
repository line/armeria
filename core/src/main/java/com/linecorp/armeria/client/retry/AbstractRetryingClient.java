/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;

import io.netty.channel.EventLoop;
import io.netty.util.AsciiString;

/**
 * A {@link Client} decorator that handles failures of remote invocation and retries requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractRetryingClient
        <I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {
    /**
     * The header which indicates the retry count of a {@link Request}.
     * The server might use this value to reject excessive retries, etc.
     */
    public static final AsciiString ARMERIA_RETRY_COUNT = HttpHeaderNames.of("armeria-retry-count");

    private final RetryConfigMapping<O> retryMapping;

    @Nullable
    private final RetryConfig<O> retryConfig;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractRetryingClient(
            Client<I, O> delegate, RetryConfigMapping<O> retryMapping, @Nullable RetryConfig<O> retryConfig) {
        super(delegate);
        this.retryMapping = requireNonNull(retryMapping, "retryMapping");
        this.retryConfig = retryConfig;
    }

    abstract RetryContext newRetryContext(
            Client<I, O> delegate,
            ClientRequestContext ctx,
            I req,
            RetryConfig<O> config);

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final RetryConfig<O> config =
                retryConfig != null ? retryConfig
                                    : requireNonNull(retryMapping.get(ctx, req),
                                                     "retryMapping.get() returned null");
        final RetryContext rctx = newRetryContext(unwrap(), ctx, req, config);

        if (rctx.retryEventLoop().inEventLoop()) {
            prepareAndRetry(rctx);
        } else {
            rctx.retryEventLoop().execute(() -> prepareAndRetry(rctx));
        }

        return rctx.res();
    }

    private void prepareAndRetry(RetryContext rctx) {
        assert rctx.retryEventLoop().inEventLoop();

        try {
            prepareRetry(rctx);
        } catch (Throwable cause) {
            handleUnexpectedException(rctx, cause);
            return;
        }

        // retry() does not throw.
        retry(rctx, null);
    }

    private void prepareRetry(RetryContext rctx) {
        rctx.res().whenComplete().handle((result, cause) -> {
            final Throwable abortCause;
            if (cause != null) {
                abortCause = cause;
            } else {
                abortCause = AbortedStreamException.get();
            }
            if (rctx.retryEventLoop().inEventLoop()) {
                rctx.req().abort(abortCause);
            } else {
                rctx.retryEventLoop().execute(() -> rctx.req().abort(abortCause));
            }
            return null;
        });

        // The request could complete at any moment.
        // In that case, let us make sure that we close the scheduler so we
        // do not run any retry task unnecessarily.
        // That said, running retry() even with a completed response is handled by
        // rctx.request().executeAttempt() which throws an AbortedAttemptException which we handle gracefully.
        rctx.req().whenComplete().handle((res, cause) -> {
            assert rctx.retryEventLoop().inEventLoop();
            // Make sure we do not unnecessarily run any retry task.
            rctx.scheduler().close();

            if (cause != null) {
                rctx.resFuture().completeExceptionally(cause);
            } else {
                rctx.resFuture().complete(res);
            }

            return null;
        });

        // If something goes wrong with the scheduler, e.g. when the ClientFactory closes and the scheduler is
        // unable to schedule retry tasks, we want to make sure we gracefully abort the request with that
        // exception.
        rctx.scheduler().whenClosed().handle((unused, cause) -> {
            assert rctx.retryEventLoop().inEventLoop();

            if (cause == null) {
                cause = new IllegalStateException(
                        "retry scheduler was closed before the request was completed");
            }

            rctx.req().abort(cause);
            return null;
        });
    }

    // NOTE:
    // - Does not throw.
    // - Must run on the retryEventLoop.
    // - The first call must be done from execute() above.
    // - Subsequent calls must only be issued in the retry task scheduled by `rctx.scheduler()`.
    //   The corresponding `scheduler.schedule()` calls must all be done from ´tryScheduleRetryAfter()´ below.
    private void retry(
            RetryContext rctx,
            @Nullable Backoff previousBackoff
    ) {
        try {
            // First record the execution of the following attempt. This increases the attempt count
            // and the attempt count for the backoff. Also see (A) for a correctness argument.
            rctx.counter().consumeAttemptFrom(previousBackoff);

            rctx.req()
                .executeAttempt(rctx.delegate())
                .handle((executionResult, cause) -> {
                    assert rctx.retryEventLoop().inEventLoop();

                    if (cause != null) {
                        rctx.req().abort(
                                new IllegalStateException("expected RetriedRequest to be completed"));

                        if (cause instanceof AbortedAttemptException) {
                            // The attempt was aborted in the course of executing it. This can happen when
                            // we execute an attempt on a completed request or when the request is completed
                            // while RetriedRequest is waiting for the response of the attempt.
                            return null;
                        }

                        return null;
                    }

                    // An empty backoff means that the RetryRule to commit this attempt.
                    if (executionResult.decision().backoff() == null) {
                        rctx.req().commit(executionResult.attemptNumber());
                        return null;
                    }

                    // Note that applying the pushback needs to be done before the call
                    // to tryScheduleRetryAfter`.
                    if (executionResult.minimumBackoffMillis() > 0) {
                        rctx.scheduler().applyMinimumBackoffMillisForNextRetry(
                                executionResult.minimumBackoffMillis());
                    }

                    tryScheduleRetryAfter(rctx, executionResult.decision().backoff());

                    if (!rctx.scheduler().hasNextRetryTask()) {
                        // We are not going to retry again so we are the last attempt. Let us commit it even if
                        // the response was deemed to be unsatisfactory by the RetryRule (backoff != null).
                        rctx.req().commit(executionResult.attemptNumber());
                    } else {
                        // The responsibility of aborting the RetriedRequest is now with the retry
                        // scheduled by `tryScheduleRetryAfter()`. Thus, we can safely abort this attempt now.
                        rctx.req().abort(executionResult.attemptNumber(), AbortedStreamException.get());
                    }

                    return null;
                })
                .exceptionally(cause -> {
                    handleUnexpectedException(rctx, cause);
                    return null;
                });
        } catch (Throwable cause) {
            handleUnexpectedException(rctx, cause);
        }
    }

    // NOTE: Must run on the retryEventLoop.
    private void tryScheduleRetryAfter(RetryContext rctx, Backoff nextBackoff) {
        if (rctx.counter().hasReachedMaxAttempts()) {
            return;
        }

        final long nextRetryDelayMillisFromBackoff = nextBackoff.nextDelayMillis(
                // NOTE: `attemptsSoFarWithBackoff` is read-only and in particular it does not increase
                // the attempt count for the backoff.
                // +1 because nextDelayMillis counts the original request as one attempt for the backoff.
                rctx.counter().attemptsSoFarWithBackoff(nextBackoff) + 1
        );

        if (nextRetryDelayMillisFromBackoff < 0) {
            // We exceeded the attempt limit for the backoff.
            return;
        }

        // (A): We are under `maxAttempts` have also not exceeded the backoff attempt limit so let us
        // try to schedule the next retry task.
        // NOTE: The scheduler guarantees *if* we run this retry task, there will be no retry task run between
        // this call and the execution of the retry task. This guarantees two things:
        // 1. The attempt number and the attempt number for the backoff now and upon execution of the retry task
        //   are the same.
        // 2. Based on 1., the delay we use to schedule the retry task is indeed the one we consume in the retry
        //    task via `counter.consumeAttemptFrom(nextBackoff)`.
        rctx.scheduler().trySchedule(/* retry task */ () -> retry(rctx, nextBackoff),
                                                      nextRetryDelayMillisFromBackoff);
    }

    private void handleUnexpectedException(RetryContext rctx, Throwable cause) {
        assert rctx.retryEventLoop().inEventLoop();
        // Aborting the request will trigger the cleanup logic in prepareRetry().
        rctx.req().abort(new IllegalStateException("Unexpected exception during retrying", cause));
    }

    protected final class RetryContext {
        final EventLoop retryEventLoop;
        final RetriedRequest<I, O> req;
        final RetryScheduler scheduler;
        final RetryCounter counter;
        final Client<I, O> delegate;
        final O res;
        final CompletableFuture<O> resFuture;

        RetryContext(EventLoop retryEventLoop, RetriedRequest<I, O> req,
                     RetryScheduler scheduler, RetryCounter counter, Client<I, O> delegate,
                     O res,
                     CompletableFuture<O> resFuture) {
            this.retryEventLoop = retryEventLoop;
            this.req = req;
            this.scheduler = scheduler;
            this.counter = counter;
            this.delegate = delegate;
            this.res = res;
            this.resFuture = resFuture;
        }

        EventLoop retryEventLoop() {
            return retryEventLoop;
        }

        RetriedRequest<I, O> req() {
            return req;
        }

        O res() {
            return res;
        }

        CompletableFuture<O> resFuture() {
            return resFuture;
        }

        RetryScheduler scheduler() {
            return scheduler;
        }

        RetryCounter counter() {
            return counter;
        }

        Client<I, O> delegate() {
            return delegate;
        }
    }
}
