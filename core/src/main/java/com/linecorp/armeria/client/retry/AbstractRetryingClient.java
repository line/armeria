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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.retry.AbstractRetryingClient.RetrySchedulabilityDecision.Rationale;
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

    protected static final Logger logger = LoggerFactory.getLogger(AbstractRetryingClient.class);

    /**
     * The header which indicates the retry count of a {@link Request}.
     * The server might use this value to reject excessive retries, etc.
     */
    public static final AsciiString ARMERIA_RETRY_COUNT = HttpHeaderNames.of("armeria-retry-count");

    private static final AttributeKey<State> STATE =
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
        final ResponseFuturePair<O> rfp = getResponseFuturePair(ctx);

        if (!ctx.eventLoop().inEventLoop()) {
            ctx.eventLoop().execute(() -> {
                try {
                    doFirstExecute(ctx, req, rfp.response(), rfp.responseFuture());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            doFirstExecute(ctx, req, rfp.response(), rfp.responseFuture());
        }

        return rfp.response();
    }

    private void doFirstExecute(ClientRequestContext ctx, I req, O res, CompletableFuture<O> resFuture)
            throws Exception {
        final RetryConfig<O> config = mapping.get(ctx, req);
        requireNonNull(config, "mapping.get() returned null");

        final State state;
        if (ctx.responseTimeoutMillis() <= 0 || ctx.responseTimeoutMillis() == Long.MAX_VALUE) {
            final RetryScheduler scheduler = new RetryScheduler(ctx.eventLoop());
            state = new State(config, scheduler);
        } else {
            final long responseTimeoutTimeNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis());
            final RetryScheduler scheduler = new RetryScheduler(ctx.eventLoop(), responseTimeoutTimeNanos);
            state = new State(config, scheduler, responseTimeoutTimeNanos);
        }

        ctx.setAttr(STATE, state);

        state.whenRetryingComplete().handle((f, t) -> {
            state.scheduler().close();
            return null;
        });

        doExecute(ctx, req, res, resFuture);
    }

    abstract ResponseFuturePair<O> getResponseFuturePair(ClientRequestContext ctx);

    protected static class ResponseFuturePair<O> {
        private final O response;
        private final CompletableFuture<O> responseFuture;

        ResponseFuturePair(O response, CompletableFuture<O> responseFuture) {
            this.response = requireNonNull(response, "response");
            this.responseFuture = requireNonNull(responseFuture, "responseFuture");
        }

        public O response() {
            return response;
        }

        public CompletableFuture<O> responseFuture() {
            return responseFuture;
        }
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
    protected abstract void doExecute(ClientRequestContext ctx, I req, O res, CompletableFuture<O> resFuture)
            throws Exception;

    /**
     * This should be called when retrying is finished.
     */
    protected static void onRetryingComplete(ClientRequestContext ctx,
                                             ClientRequestContext attemptCtx) {
        ctx.logBuilder().endResponseWithChild(attemptCtx.log());

        state(ctx).complete(attemptCtx);
    }

    protected static void onRetryingCompleteExceptionally(ClientRequestContext ctx,
                                                          Throwable cause) {
        ctx.logBuilder().endResponse(cause);
        state(ctx).completeExceptionally(cause);
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

    protected static void onAttemptStarted(ClientRequestContext ctx,
                                           ClientRequestContext attemptCtx,
                                           Consumer<@Nullable Throwable> onAttemptAbortedHandler
    ) {
        requireNonNull(ctx, "ctx");
        requireNonNull(attemptCtx, "attemptCtx");
        // todo(szymon) [Q]: should we track the attemptCtxs and check if we have multiple attempts started?
        state(ctx).incrementPendingAttemptCount();
        state(ctx).whenRetryingComplete().handle((winningAttemptCtx, cause) -> {
            if (attemptCtx == winningAttemptCtx) {
                return null;
            }

            onAttemptAbortedHandler.accept(cause);
            return null;
        });
        logger.debug("onAttemptStarted: {}. numRemainingPendingAttempts = {}, hasScheduledRetryTask={}",
                     ctx, state(ctx).numPendingAttempts, state(ctx).scheduler().hasScheduledRetryTask());

    }

    protected boolean onAttemptEnded(ClientRequestContext ctx) {
        final int numRemainingPendingAttempts = state(ctx).decrementPendingAttemptCount();
        logger.debug("onAttemptEnded: {}. numRemainingPendingAttempts = {}, hasScheduledRetryTask={}",
                     ctx, numRemainingPendingAttempts, state(ctx).scheduler().hasScheduledRetryTask());
        return numRemainingPendingAttempts > 0 || state(ctx).scheduler().hasScheduledRetryTask();
    }

    // an attempt did not trigger a retry. when does the attempt end?

    protected static boolean isRetryingComplete(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return state(ctx).whenRetryingComplete().isDone();
    }

    protected static void scheduleNextRetry(ClientRequestContext ctx,
                                            Runnable retryTask,
                                            long retryTimeNanos,
                                            Consumer<? super Throwable> actionOnException) {
        requireNonNull(ctx, "ctx");
        requireNonNull(actionOnException, "actionOnException");
        requireNonNull(retryTask, "retryTask");

        final RetryScheduler scheduler = state(ctx).scheduler();

        scheduleNextRetry(ctx, retryTask, retryTimeNanos,
                          scheduler.getEarliestNextRetryTimeNanos(), actionOnException);
    }

    protected static void scheduleNextRetry(ClientRequestContext ctx,
                                            Runnable retryTask,
                                            long retryTimeNanos,
                                            long earliestNextRetryTimeFromServerNanos,
                                            Consumer<? super Throwable> actionOnException) {
        requireNonNull(ctx, "ctx");
        requireNonNull(actionOnException, "actionOnException");
        requireNonNull(retryTask, "retryTask");

        final RetryScheduler scheduler = state(ctx).scheduler();

        scheduler.addEarliestNextRetryTimeNanos(earliestNextRetryTimeFromServerNanos);
        scheduler.schedule(retryTask, retryTimeNanos, actionOnException);
    }

    protected static void addEarliestNextRetryTimeNanos(ClientRequestContext ctx,
                                                        long earliestNextRetryTimeNanos) {
        requireNonNull(ctx, "ctx");
        final RetryScheduler scheduler = state(ctx).scheduler();
        scheduler.addEarliestNextRetryTimeNanos(earliestNextRetryTimeNanos);
        scheduler.rescheduleCurrentRetryTaskIfTooEarly();
    }

    /**
     * Resets the {@link ClientRequestContext#responseTimeoutMillis()}.
     *
     * @return {@code true} if the response timeout is set, {@code false} if it can't be set due to the timeout
     */
    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final boolean updateResponseTimeout(ClientRequestContext ctx) {
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

    protected final RetrySchedulabilityDecision canScheduleWith(ClientRequestContext ctx, Backoff backoff) {
        return canScheduleWith(ctx, backoff, -1);
    }

    protected final RetrySchedulabilityDecision canScheduleWith(ClientRequestContext ctx, Backoff backoff,
                                                                long millisFromServer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(backoff, "backoff");
        final State state = state(ctx);
        final RetryScheduler scheduler = state.scheduler();
        final long nowTimeNanos = System.nanoTime();

        final long earliestNextRetryTimeNanos = Math.max(scheduler.getEarliestNextRetryTimeNanos(),
                                                         millisFromServer < 0 ?
                                                         scheduler.getEarliestNextRetryTimeNanos() :
                                                         nowTimeNanos + TimeUnit.MILLISECONDS.toNanos(
                                                                 millisFromServer));

        if (state.timeoutForWholeRetryEnabled()) {
            if (earliestNextRetryTimeNanos > state.responseTimeoutTimeNanos()) {
                logger.debug("The earliest next retry time {} is after the response timeout time {}. "
                             + "Not scheduling a retry.",
                             earliestNextRetryTimeNanos, state.responseTimeoutTimeNanos());
                return new RetrySchedulabilityDecision(Rationale.EXCEEDS_RESPONSE_TIMEOUT, Long.MAX_VALUE,
                                                       scheduler.getEarliestNextRetryTimeNanos());
            }
        }

        final int nextAttemptNo = state.nextAttemptNoWithBackoff(backoff);
        if (nextAttemptNo < 0) {
            logger.debug("Exceeded the default number of max attempt: {}", state.config.maxTotalAttempts());
            return new RetrySchedulabilityDecision(Rationale.NO_MORE_ATTEMPTS, Long.MAX_VALUE,
                                                   earliestNextRetryTimeNanos);
        }

        final long nextDelay = backoff.nextDelayMillis(nextAttemptNo);
        if (nextDelay < 0) {
            logger.debug("Exceeded the number of max attempts in the backoff: {}", backoff);
            return new RetrySchedulabilityDecision(Rationale.NO_MORE_ATTEMPTS_IN_BACKOFF, Long.MAX_VALUE,
                                                   earliestNextRetryTimeNanos);
        }

        final long nextRetryTimeNanos = Math.max(nowTimeNanos + TimeUnit.MILLISECONDS.toNanos(nextDelay),
                                                 earliestNextRetryTimeNanos);

        if (state(ctx).timeoutForWholeRetryEnabled()) {
            if (nextRetryTimeNanos > state(ctx).responseTimeoutTimeNanos()) {
                logger.debug("The next retry time {} is after the response timeout time {}. "
                             + "Not scheduling a retry.",
                             nextRetryTimeNanos, state(ctx).responseTimeoutTimeNanos());
                return new RetrySchedulabilityDecision(Rationale.EXCEEDS_RESPONSE_TIMEOUT, Long.MAX_VALUE,
                                                       earliestNextRetryTimeNanos);
            }
        }

        if (scheduler.hasAlreadyRetryScheduledBefore(nextRetryTimeNanos, earliestNextRetryTimeNanos)) {
            return new RetrySchedulabilityDecision(Rationale.HAS_EARLIER_RETRY,
                                                   nextRetryTimeNanos, earliestNextRetryTimeNanos);
        }

        // todo(szymon): do we want to wait for acquisition when we schedule it?
        state(ctx).acquireAttemptNoWithCurrentBackoff(backoff);

        return new RetrySchedulabilityDecision(Rationale.SCHEDULABLE, nextRetryTimeNanos,
                                               earliestNextRetryTimeNanos);
    }

    /**
     * Returns the next delay which retry will be made after. The delay will be:
     *
     * <p>{@code Math.min(responseTimeoutMillis, Backoff.nextDelayMillis(int))}
     *
     * @return the number of milliseconds to wait for before attempting a retry. -1 if the
     * {@code currentAttemptNo} exceeds the {@code maxAttempts} or the {@code nextDelay} is after
     * the moment which timeout happens.
     */
    protected final long getNextDelay(ClientRequestContext ctx, Backoff backoff) {
        return getNextDelay(ctx, backoff, -1);
    }

    /**
     * Returns the next delay which retry will be made after. The delay will be:
     *
     * <p>{@code Math.min(responseTimeoutMillis, Math.max(Backoff.nextDelayMillis(int),
     * millisFromServer))}
     * <p>
     * If delay is non-negative, we expect a retry to be issued and so a retry attempt is consumed.
     *
     * @return the number of milliseconds to wait for before attempting a retry. -1 if either
     * - {@code currentAttemptNo} exceeds the {@code maxAttempts} or
     * - the {@code nextDelay} is after the moment which timeout happens or
     * - there is a pending retry task that is shorter than the next delay.
     */
    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final long getNextDelay(ClientRequestContext ctx, Backoff backoff,
                                      long millisFromServer) {
        // todo(szymon): map to canschedulewith.
        return -1;
    }

    /**
     * Returns the total number of attempts of the current request represented by the specified
     * {@link ClientRequestContext}.
     */
    protected static int getTotalAttempts(ClientRequestContext ctx) {
        final State state = ctx.attr(STATE);
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

    private static State state(ClientRequestContext ctx) {
        final State state = ctx.attr(STATE);
        assert state != null;
        return state;
    }

    protected static final class RetrySchedulabilityDecision {
        enum Rationale {
            SCHEDULABLE(true),
            NO_MORE_ATTEMPTS(false),
            NO_MORE_ATTEMPTS_IN_BACKOFF(false),
            EXCEEDS_RESPONSE_TIMEOUT(false),
            HAS_EARLIER_RETRY(false);

            private final boolean canSchedule;

            Rationale(boolean canSchedule) {
                this.canSchedule = canSchedule;
            }

            public boolean canSchedule() {
                return canSchedule;
            }
        }

        private final Rationale outcome;
        private final long nextRetryTimeNanos;
        private final long earliestNextRetryTimeNanos;

        RetrySchedulabilityDecision(Rationale outcome, long nextRetryTimeNanos,
                                    long earliestNextRetryTimeNanos) {
            requireNonNull(outcome, "outcome");
            checkArgument(earliestNextRetryTimeNanos <= nextRetryTimeNanos);

            this.outcome = outcome;
            this.nextRetryTimeNanos = nextRetryTimeNanos;
            this.earliestNextRetryTimeNanos = earliestNextRetryTimeNanos;
        }

        long nextRetryTimeNanos() {
            return nextRetryTimeNanos;
        }

        long earliestNextRetryTimeNanos() {
            return earliestNextRetryTimeNanos;
        }

        boolean canSchedule() {
            return outcome.canSchedule();
        }

        @Override
        public String toString() {
            return "RetrySchedulabilityDecision{" +
                   "outcome=" + outcome +
                   ", nextRetryTimeNanos=" + nextRetryTimeNanos +
                   ", earliestNextRetryTimeNanos=" + earliestNextRetryTimeNanos +
                   '}';
        }
    }

    private static final class State {
        private final RetryConfig<?> config;
        private final long deadlineNanos;
        private final boolean isTimeoutEnabled;

        private final RetryScheduler retryScheduler;
        private int numPendingAttempts;

        private final CompletableFuture<ClientRequestContext> retryingCompleteFuture;

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
         * remaining {@link #responseTimeoutMillisForAttempt}.
         *
         * @return 0 if the response timeout for both of each request and whole retry is disabled or
         * -1 if the elapsed time from the first request has passed {@code responseTimeoutMillis}
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

        long responseTimeoutMillis() {
            assert isTimeoutEnabled;
            return Math.max(TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()), -1);
        }

        long responseTimeoutTimeNanos() {
            assert isTimeoutEnabled;
            return deadlineNanos;
        }

        int nextAttemptNoWithBackoff(Backoff backoff) {
            if (totalAttemptNo >= config.maxTotalAttempts()) {
                return -1;
            }

            if (lastBackoff != backoff) {
                return 1;
            }
            return currentAttemptNoWithLastBackoff + 1;
        }

        void acquireAttemptNoWithCurrentBackoff(Backoff backoff) {
            checkState((totalAttemptNo + 1) <= config.maxTotalAttempts(),
                       "Exceeded the maximum number of attempts: %s", config.maxTotalAttempts());

            totalAttemptNo++;

            if (lastBackoff != backoff) {
                lastBackoff = backoff;
                currentAttemptNoWithLastBackoff = 1;
                return;
            }

            currentAttemptNoWithLastBackoff++;
        }

        void incrementPendingAttemptCount() {
            numPendingAttempts++;
        }

        int decrementPendingAttemptCount() {
            checkArgument(numPendingAttempts > 0, "numPendingAttempts must be greater than 0. did you call "
                                                  + "incrementPendingAttemptCount() before?");
            return --numPendingAttempts;
        }

        void complete(ClientRequestContext winningAttemptCtx) {
            retryingCompleteFuture.complete(winningAttemptCtx);
        }

        void completeExceptionally(Throwable cause) {
            retryingCompleteFuture.completeExceptionally(cause);
        }

        CompletableFuture<ClientRequestContext> whenRetryingComplete() {
            return retryingCompleteFuture;
        }
    }
}
