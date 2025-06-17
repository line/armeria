/*
 * Copyright 2025 LY Corporation
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
package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.retry.RetrySchedulingException.Type;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.client.ClientUtil;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

/**
 * A {@link Client} decorator that handles failures of remote invocation and retries requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractRetryingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractRetryingClient.class);

    /**
     * The header which indicates the retry count of a {@link Request}.
     * The server might use this value to reject excessive retries, etc.
     */
    public static final AsciiString ARMERIA_RETRY_COUNT = HttpHeaderNames.of("armeria-retry-count");

    private static final AttributeKey<State<?>> STATE =
            AttributeKey.valueOf(AbstractRetryingClient.class, "STATE");

    private final RetryConfigMapping<O> mapping;

    @Nullable
    private final RetryConfig<O> retryConfig;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractRetryingClient(
            Client<I, O> delegate, RetryConfigMapping<O> mapping, @Nullable RetryConfig<O> retryConfig) {
        super(delegate);
        this.mapping = requireNonNull(mapping, "mapping");
        this.retryConfig = retryConfig;
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final RetryConfig<O> config = mapping.get(ctx, req);
        requireNonNull(config, "mapping.get() returned null");

        final State<O> state;
        if (ctx.responseTimeoutMillis() <= 0 || ctx.responseTimeoutMillis() == Long.MAX_VALUE) {
            final RetryScheduler scheduler = new RetryScheduler(ctx.eventLoop());
            state = new State<>(config, scheduler);
        } else {
            final long responseTimeoutTimeNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis());
            final RetryScheduler scheduler = new RetryScheduler(ctx.eventLoop(), responseTimeoutTimeNanos);
            state = new State<>(config, scheduler, responseTimeoutTimeNanos);
        }

        state.whenRetryingComplete().handle((f, t) -> {
            state.scheduler().shutdown();
            return null;
        });

        ctx.setAttr(STATE, state);
        return doExecute(ctx, req);
    }

    /**
     * Returns the current {@link RetryConfigMapping} set for this client.
     */
    protected final RetryConfigMapping<O> mapping() {
        return mapping;
    }

    /**
     * Invoked by {@link #execute(ClientRequestContext, Request)}
     * after the deadline for response timeout is set.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req)
            throws Exception;

    /**
     * This should be called when retrying is finished.
     */
    protected static void completeRetryingIfNoPendingAttempts(ClientRequestContext ctx) {
        final State<?> state = state(ctx);
        synchronized (state) {
            state.completeIfNoPendingAttempts();
        }
    }

    /**
     * todo(szymon): [doc].
     */
    protected static void completeRetryingExceptionally(ClientRequestContext ctx,
                                                        Throwable cause) {
        logger.debug("onRetryingCompleteExceptionally: {}", ctx, cause);
        final State<?> state = state(ctx);
        synchronized (state) {
            state.completeExceptionally(cause);
        }
    }

    /**
     * Returns the {@link RetryRule}.
     *
     * @throws IllegalStateException if the {@link RetryRule} is not set
     */
    protected final RetryRule retryRule() {
        checkState(retryConfig != null, "No retryRule set. Are you using RetryConfigMapping?");
        final RetryRule retryRule = retryConfig.retryRule();
        checkState(retryRule != null, "retryRule is not set.");
        return retryRule;
    }

    /**
     * Fetches the {@link RetryConfig} that was mapped by the configured {@link RetryConfigMapping} for a given
     * logical request.
     */
    final RetryConfig<O> mappedRetryConfig(ClientRequestContext ctx) {
        @SuppressWarnings("unchecked")
        final RetryConfig<O> config = (RetryConfig<O>) state(ctx).config;
        return config;
    }

    /**
     * Returns the {@link RetryRuleWithContent}.
     *
     * @throws IllegalStateException if the {@link RetryRuleWithContent} is not set
     */
    protected final RetryRuleWithContent<O> retryRuleWithContent() {
        checkState(retryConfig != null, "No retryRuleWithContent set. Are you using RetryConfigMapping?");
        final RetryRuleWithContent<O> retryRuleWithContent = retryConfig.retryRuleWithContent();
        checkState(retryRuleWithContent != null, "retryRuleWithContent is not set.");
        return retryRuleWithContent;
    }

    /**
     * todo(szymon): [doc].
     */
    protected void startRetryAttempt(ClientRequestContext ctx,
                                     ClientRequestContext attemptCtx,
                                     BiConsumer<ClientRequestContext, O> onWinHandler,
                                     BiConsumer<ClientRequestContext, @Nullable Throwable>
                                             onAttemptAbortedHandler
    ) {
        requireNonNull(ctx, "ctx");
        requireNonNull(attemptCtx, "attemptCtx");
        requireNonNull(onWinHandler, "onWinHandler");
        requireNonNull(onAttemptAbortedHandler, "onAttemptAbortedHandler");

        final State<O> state = state(ctx);

        synchronized (state) {
            if (isRetryingComplete(ctx)) {
                onAttemptAbortedHandler.accept(attemptCtx,
                                               new IllegalStateException("Retrying is already complete."));
                return;
            }

            state.startAttempt(attemptCtx);
            state.whenRetryingComplete().handle((winningAttempt, cause) -> {
                if (winningAttempt == null) {
                    // If the retrying is complete exceptionally, we need to call the onAttemptAbortedHandler.
                    onAttemptAbortedHandler.accept(winningAttempt.ctx(), cause);
                    return null;
                }

                final ClientRequestContext winningAttemptCtx = winningAttempt.ctx();
                final O winningAttemptRes = winningAttempt.res();
                assert winningAttemptRes != null;

                if (attemptCtx == winningAttemptCtx) {
                    onWinHandler.accept(winningAttemptCtx, winningAttemptRes);
                    return null;
                }

                onAttemptAbortedHandler.accept(attemptCtx, cause);
                return null;
            });
        }
    }

    /**
     * todo(szymon): [doc].
     */
    protected void completeRetryAttempt(ClientRequestContext ctx,
                                        ClientRequestContext attemptCtx,
                                        O attemptRes, boolean isWinning) {
        if (isRetryingComplete(ctx)) {
            // The complete handler of this attempt was already executed (provided that `attemptCtx`
            // was registered with `startRetryAttempt` as by the contract of `completeRetryAttempt`.
            return;
        }

        final State<O> state = state(ctx);

        synchronized (state) {
            state.completeAttempt(attemptCtx, attemptRes);

            if (isWinning) {
                state.complete();
            }
        }
    }

    /**
     * todo(szymon): [doc].
     */
    protected static boolean isRetryingComplete(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");

        final State<?> state = state(ctx);

        synchronized (state) {
            return state.whenRetryingComplete().isDone();
        }
    }

    /**
     * todo(szymon): [doc].
     */
    protected void scheduleNextRetry(ClientRequestContext ctx,
                                     Consumer<Integer> retryTask,
                                     Backoff backoff,
                                     Consumer<? super Throwable> actionOnException) {
        scheduleNextRetry(ctx, retryTask, backoff, -1, actionOnException);
    }

    /**
     * todo(szymon): [doc].
     */
    protected static void scheduleNextRetry(ClientRequestContext ctx,
                                            Consumer<Integer> retryTask,
                                            Backoff backoff,
                                            long retryDelayFromServerMillis,
                                            Consumer<? super Throwable> actionOnException) {
        requireNonNull(ctx, "ctx");
        requireNonNull(retryTask, "retryTask");
        requireNonNull(backoff, "backoff");
        requireNonNull(actionOnException, "actionOnException");

        if (isRetryingComplete(ctx)) {
            actionOnException.accept(new RetrySchedulingException(Type.RETRYING_ALREADY_COMPLETED));
            return;
        }

        final State<?> state = state(ctx);
        final RetryScheduler scheduler = state.scheduler();

        final long nowTimeNanos = System.nanoTime();

        long earliestRetryTimeNanos = retryDelayFromServerMillis >= 0 ?
                                      (nowTimeNanos + TimeUnit.MILLISECONDS.toNanos(
                                              retryDelayFromServerMillis))
                                                                      : Long.MIN_VALUE;

        if (state.timeoutForWholeRetryEnabled() &&
            earliestRetryTimeNanos > state.responseTimeoutTimeNanos()) {
            actionOnException.accept(
                    new RetrySchedulingException(
                            Type.DELAY_FROM_SERVER_EXCEEDS_RESPONSE_TIMEOUT));
            return;
        }

        // Even when we cannot schedule the retry task, we want to respect the
        // minimum retry delay from the server.
        earliestRetryTimeNanos = scheduler.addEarliestNextRetryTimeNanos(earliestRetryTimeNanos);

        final int attemptNoWithBackoff = state.numAttemptsSoFarWithBackoff(backoff);
        if (attemptNoWithBackoff < 0) {
            scheduler.rescheduleCurrentRetryTaskIfTooEarly();
            actionOnException.accept(
                    new RetrySchedulingException(RetrySchedulingException.Type.NO_MORE_ATTEMPTS_IN_RETRY));
            return;
        }

        final long retryDelayMillis = backoff.nextDelayMillis(attemptNoWithBackoff);
        if (retryDelayMillis < 0) {
            scheduler.rescheduleCurrentRetryTaskIfTooEarly();
            actionOnException.accept(
                    new RetrySchedulingException(RetrySchedulingException.Type.NO_MORE_ATTEMPTS_IN_BACKOFF));
            return;
        }

        final long retryTimeNanos = Math.max(nowTimeNanos + TimeUnit.MILLISECONDS.toNanos(retryDelayMillis),
                                             earliestRetryTimeNanos);
        if (state.timeoutForWholeRetryEnabled() && retryTimeNanos > state.responseTimeoutTimeNanos()) {
            scheduler.rescheduleCurrentRetryTaskIfTooEarly();
            // This cannot be the minimum delay from the server, because we already checked it above.
            actionOnException.accept(
                    new RetrySchedulingException(
                            Type.DELAY_FROM_BACKOFF_EXCEEDS_RESPONSE_TIMEOUT));
            return;
        }

        synchronized (state) {
            state.startRetryTask();
        }
        scheduler.schedule(() -> {

            final int thisAttemptNo;
            // *, see comment above.
            synchronized (state) {
                if (isRetryingComplete(ctx)) {
                    state.completeRetryTask();
                    actionOnException.accept(new RetrySchedulingException(Type.RETRYING_ALREADY_COMPLETED));
                    return;
                }

                thisAttemptNo = state.acquireAttemptNoWithCurrentBackoff(backoff);
            }

            retryTask.accept(thisAttemptNo);

            synchronized (state) {
                state.completeRetryTask();
            }

            completeRetryingIfNoPendingAttempts(ctx);
        }, retryTimeNanos, earliestRetryTimeNanos, cause -> {
            synchronized (state) {
                state.completeRetryTask();
            }
            actionOnException.accept(cause);
        });
    }

    // todo(szymon): improve documentation for this method

    /**
     * Resets the {@link ClientRequestContext#responseTimeoutMillis()}.
     *
     * @return {@code true} if the response timeout is set, {@code false} if it can't be set due to the timeout
     */
    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final boolean setResponseTimeout(ClientRequestContext ctx) {
        // We do not need to acquire a lock on the state as this method body is thread-safe.
        requireNonNull(ctx, "ctx");
        final long responseTimeoutMillis = state(ctx).responseTimeoutMillisForAttempt();
        if (responseTimeoutMillis < 0) {
            return false;
        } else if (responseTimeoutMillis == 0) {
            ctx.clearResponseTimeout();
            return true;
        } else {
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, responseTimeoutMillis);
            return true;
        }
    }

    /**
     * Returns the total number of attempts of the current request represented by the specified
     * {@link ClientRequestContext}.
     */
    protected static int getTotalAttempts(ClientRequestContext ctx) {
        // We do not need to acquire a lock on the state as this method body is thread-safe.
        final State<?> state = ctx.attr(STATE);
        if (state == null) {
            return 0;
        }
        return state.totalAttemptNo;
    }

    /**
     * Creates a new derived {@link ClientRequestContext} for a retrying attempt, replacing the requests.
     * If {@link ClientRequestContext#endpointGroup()} exists, a new {@link Endpoint} will be selected.
     */
    protected static ClientRequestContext newAttemptContext(ClientRequestContext ctx,
                                                            @Nullable HttpRequest req,
                                                            @Nullable RpcRequest rpcReq,
                                                            boolean initialAttempt) {
        return ClientUtil.newDerivedContext(ctx, req, rpcReq, initialAttempt);
    }

    @SuppressWarnings("unchecked")
    private static <O extends Response> State<O> state(ClientRequestContext ctx) {
        final State<O> state = (State<O>) ctx.attr(STATE);
        assert state != null;
        return state;
    }

    private static final class State<O extends Response> {
        static class Attempt<O> {
            private final ClientRequestContext attemptCtx;
            private @Nullable O attemptRes;

            private boolean isCompleted;

            Attempt(ClientRequestContext attemptCtx, @Nullable O attemptRes) {
                this.attemptCtx = requireNonNull(attemptCtx, "attemptCtx");
                this.attemptRes = attemptRes;
                isCompleted = this.attemptRes != null;
            }

            public ClientRequestContext ctx() {
                return attemptCtx;
            }

            @Nullable
            public O res() {
                return attemptRes;
            }

            public void setRes(O res) {
                checkState(!isCompleted);
                isCompleted = true;
                attemptRes = requireNonNull(res, "res");
            }
        }

        private final RetryConfig<?> config;
        private final long deadlineNanos;
        private final boolean isTimeoutEnabled;

        private final Map<ClientRequestContext, Attempt<O>> activeAttempts = new HashMap<>();

        private final RetryScheduler retryScheduler;

        // An attempt is considered scheduled between two points:
        // - right before its retry task is scheduled
        // - right after the retry task finishes execution
        //
        // As a consequence, the retry task must call startAttempt() synchronously.
        // If it does not, we may assume there are no active or scheduled attempts
        // once the task ends, and stop retrying too early.
        private int numScheduledAttempts;

        private @Nullable Attempt<O> lastAttempt;

        private final CompletableFuture<Attempt<O>> retryingCompleteFuture;

        @Nullable
        private Backoff lastBackoff;
        private int currentAttemptNoWithLastBackoff;
        // Starting with 1
        private int totalAttemptNo;

        State(RetryConfig<?> config, RetryScheduler retryScheduler) {
            this.config = config;
            this.retryScheduler = retryScheduler;
            totalAttemptNo = 1;
            retryingCompleteFuture = new CompletableFuture<>();
            deadlineNanos = 0;
            isTimeoutEnabled = false;
        }

        State(RetryConfig<?> config, RetryScheduler retryScheduler, long responseTimeoutTimeNanos) {
            this.config = config;
            this.retryScheduler = retryScheduler;
            totalAttemptNo = 1;
            retryingCompleteFuture = new CompletableFuture<>();
            deadlineNanos = responseTimeoutTimeNanos;
            isTimeoutEnabled = true;
        }

        RetryScheduler scheduler() {
            return retryScheduler;
        }

        /**
         * Returns the smaller value between {@link RetryConfig#responseTimeoutMillisForEachAttempt()} and
         * remaining {@link #responseTimeoutMillisForAttempt}. This method is thread-safe.
         *
         * @return 0 if the response timeout for both of each request and whole retry is disabled or
         *         -1 if the elapsed time from the first request has passed {@code responseTimeoutMillis}
         */
        long responseTimeoutMillisForAttempt() {
            if (!timeoutForWholeRetryEnabled()) {
                return config.responseTimeoutMillisForEachAttempt();
            }

            final long actualResponseTimeoutMillis = responseTimeoutMillis();

            // Consider 0 or less than 0 of actualResponseTimeoutMillis as timed out.
            if (actualResponseTimeoutMillis <= 0) {
                return -1;
            }

            if (config.responseTimeoutMillisForEachAttempt() > 0) {
                return Math.min(config.responseTimeoutMillisForEachAttempt(), actualResponseTimeoutMillis);
            }

            return actualResponseTimeoutMillis;
        }

        boolean timeoutForWholeRetryEnabled() {
            return isTimeoutEnabled;
        }

        // Is thread-safe.
        long responseTimeoutMillis() {
            assert isTimeoutEnabled;
            return Math.max(TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()), -1);
        }

        long responseTimeoutTimeNanos() {
            assert isTimeoutEnabled;
            return deadlineNanos;
        }

        void startAttempt(ClientRequestContext attemptCtx) {
            checkState(!retryingCompleteFuture.isDone());

            logger.debug("Attempt started: {}, num attempts pending = {}, num retry task scheduled = {}",
                         attemptCtx, activeAttempts.size(), numScheduledAttempts);
            checkState(!activeAttempts.containsKey(attemptCtx),
                       "Attempt %s already exists in active attempts.", attemptCtx);
            final Attempt<O> attempt = new Attempt<>(attemptCtx, null);
            activeAttempts.put(attempt.ctx(), attempt);
        }

        void completeAttempt(ClientRequestContext attemptCtx, O attemptRes) {
            if (retryingCompleteFuture.isDone()) {
                return;
            }

            checkState(activeAttempts.containsKey(attemptCtx),
                       "Attempt %s not found in active attempts: %s", attemptCtx, activeAttempts);

            lastAttempt = activeAttempts.get(attemptCtx);
            lastAttempt.setRes(attemptRes);
            activeAttempts.remove(attemptCtx);

            completeIfNoPendingAttempts();
        }

        void startRetryTask() {
            // This can get (temporarily) above max total attempts as there is a moment between scheduling
            // a new retry task and cancelling the previous one. This is because startRetryTask() needs to be
            // increment before we call the RetryScheduler.schedule() method.
            numScheduledAttempts++;
        }

        void completeRetryTask() {
            checkState(numScheduledAttempts > 0);
            numScheduledAttempts--;
        }

        int numAttemptsSoFarWithBackoff(Backoff backoff) {
            if (totalAttemptNo >= config.maxTotalAttempts()) {
                return -1;
            }

            if (lastBackoff != backoff) {
                return 1;
            }

            return currentAttemptNoWithLastBackoff;
        }

        int acquireAttemptNoWithCurrentBackoff(Backoff backoff) {
            checkState(!retryingCompleteFuture.isDone());
            checkState((totalAttemptNo + 1) <= config.maxTotalAttempts(),
                       "Exceeded the maximum number of attempts: %s", config.maxTotalAttempts());

            totalAttemptNo++;

            if (lastBackoff != backoff) {
                lastBackoff = backoff;
                currentAttemptNoWithLastBackoff = 1;
            }

            currentAttemptNoWithLastBackoff++;

            return totalAttemptNo;
        }

        int numPendingAttempts() {
            return activeAttempts.size() + (numScheduledAttempts > 0 ? 1 : 0);
        }

        void completeIfNoPendingAttempts() {
            logger.debug(
                    "completeIfNoPendingAttempts: {}, num attempts pending = {}, num retry task scheduled = {}",
                    lastAttempt != null ? lastAttempt.ctx() : "null", numPendingAttempts(),
                    numScheduledAttempts);
            if (retryingCompleteFuture.isDone()) {
                logger.debug("completeIfNoPendingAttempts: Retrying already completed: {}",
                             lastAttempt != null ? lastAttempt.ctx() : "null");
                return;
            }

            if (numPendingAttempts() > 0) {
                logger.debug(
                        "completeIfNoPendingAttempts: Have pending attempts," +
                        " num attempts pending = {}, num retry task scheduled = {}",
                        numPendingAttempts(), numScheduledAttempts
                );
                return;
            }

            // If there are no pending attempts, we can complete the retrying.
            complete();
        }

        void complete() {
            if (retryingCompleteFuture.isDone()) {
                return;
            }

            if (lastAttempt == null) {
                completeExceptionally(new IllegalStateException("Completed retrying without any attempts."));
            } else {
                logger.debug("Completing...");
                retryingCompleteFuture.complete(lastAttempt);
            }
        }

        void completeExceptionally(Throwable cause) {
            if (retryingCompleteFuture.isDone()) {
                return;
            }

            retryingCompleteFuture.completeExceptionally(cause);
        }

        CompletableFuture<Attempt<O>> whenRetryingComplete() {
            return retryingCompleteFuture;
        }
    }
}
