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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

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
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientUtil;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

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
        final ReentrantShortLock retryLock = new ReentrantShortLock();
        if (ctx.responseTimeoutMillis() <= 0 || ctx.responseTimeoutMillis() == Long.MAX_VALUE) {
            final RetryScheduler scheduler = new RetryScheduler(retryLock, ctx.eventLoop());
            state = new State<>(retryLock, config, scheduler);
        } else {
            final long responseTimeoutTimeNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis());
            final RetryScheduler scheduler = new RetryScheduler(retryLock, ctx.eventLoop(),
                                                                responseTimeoutTimeNanos);
            state = new State<>(retryLock, config, scheduler, responseTimeoutTimeNanos);
        }

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
     * todo(szymon): [doc].
     */
    protected static void completeRetryingIfNoPendingAttempts(ClientRequestContext ctx) {
        final State<?> state = state(ctx);
        state.completeIfNoPendingAttempts();
    }

    /**
     * todo(szymon): [doc].
     */
    protected static void completeRetryingExceptionally(ClientRequestContext ctx,
                                                        Throwable cause) {
        final State<?> state = state(ctx);
        state.completeExceptionally(cause);
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
                                     O attemptRes,
                                     AttemptWinHandler<O> onWinHandler,
                                     AttemptAbortHandler<O>
                                             onAbortHandler
    ) {
        requireNonNull(ctx, "ctx");
        requireNonNull(attemptCtx, "attemptCtx");
        requireNonNull(onWinHandler, "onWinHandler");
        requireNonNull(onAbortHandler, "onAbortHandler");

        final State<O> state = state(ctx);
        final ReentrantLock retryLock = state.getLock();

        retryLock.lock();

        if (isRetryingComplete(ctx)) {
            // This really should not happen. However, let us call the abort handler to free
            // potentially expensive resources here.
            retryLock.unlock();
            onAbortHandler.accept(attemptCtx,
                                  attemptRes,
                                  new IllegalStateException("Retrying is already complete."));
            return;
        }

        state.startAttempt(attemptCtx, attemptRes, onWinHandler, onAbortHandler);
        retryLock.unlock();
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

        state(ctx).completeAttempt(attemptCtx, attemptRes, isWinning);
    }

    /**
     * todo(szymon): [doc].
     */
    protected static boolean isRetryingComplete(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");

        final State<?> state = state(ctx);

        return state.isRetryingComplete();
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
        final ReentrantLock retryLock = state.getLock();

        retryLock.lock();

        final long nowTimeNanos = System.nanoTime();

        long earliestRetryTimeNanos = retryDelayFromServerMillis >= 0 ?
                                      (nowTimeNanos + TimeUnit.MILLISECONDS.toNanos(
                                              retryDelayFromServerMillis))
                                                                      : Long.MIN_VALUE;

        if (state.timeoutForWholeRetryEnabled() &&
            earliestRetryTimeNanos > state.responseTimeoutTimeNanos()) {
            retryLock.unlock();
            actionOnException.accept(
                    new RetrySchedulingException(
                            Type.DELAY_FROM_SERVER_EXCEEDS_RESPONSE_TIMEOUT));
            return;
        }

        // Even when we cannot schedule the retry task, we want to respect the
        // minimum retry delay from the server.
        final RetryScheduler scheduler = state.scheduler();
        earliestRetryTimeNanos = scheduler.addEarliestNextRetryTimeNanos(earliestRetryTimeNanos);

        final int attemptNoWithBackoff = state.numAttemptsSoFarWithBackoff(backoff);
        if (attemptNoWithBackoff < 0) {
            retryLock.unlock();
            scheduler.rescheduleCurrentRetryTaskIfTooEarly();
            actionOnException.accept(
                    new RetrySchedulingException(RetrySchedulingException.Type.NO_MORE_ATTEMPTS_IN_RETRY));
            return;
        }

        final long retryDelayMillis = backoff.nextDelayMillis(attemptNoWithBackoff);
        if (retryDelayMillis < 0) {
            retryLock.unlock();
            scheduler.rescheduleCurrentRetryTaskIfTooEarly();
            actionOnException.accept(
                    new RetrySchedulingException(
                            RetrySchedulingException.Type.NO_MORE_ATTEMPTS_IN_BACKOFF));
            return;
        }

        final long retryTimeNanos = Math.max(nowTimeNanos + TimeUnit.MILLISECONDS.toNanos(retryDelayMillis),
                                             earliestRetryTimeNanos);
        if (state.timeoutForWholeRetryEnabled() && retryTimeNanos > state.responseTimeoutTimeNanos()) {
            retryLock.unlock();
            scheduler.rescheduleCurrentRetryTaskIfTooEarly();
            // This cannot be the minimum delay from the server, because we already checked it above.
            actionOnException.accept(
                    new RetrySchedulingException(
                            Type.DELAY_FROM_BACKOFF_EXCEEDS_RESPONSE_TIMEOUT));
            return;
        }

        final AtomicBoolean exceptionDuringScheduling = new AtomicBoolean(true);
        state.startRetryTask();
        scheduler.schedule(retryLock0 -> {
            assert retryLock == retryLock0;
            assert retryLock.isHeldByCurrentThread();
            assert retryLock.getHoldCount() == 1;

            if (isRetryingComplete(ctx)) {
                state.completeRetryTask();
                retryLock.unlock();
                actionOnException.accept(new RetrySchedulingException(Type.RETRYING_ALREADY_COMPLETED));
                return;
            }

            final int thisAttemptNo = state.acquireAttemptNoWithCurrentBackoff(backoff);
            assert thisAttemptNo >= 1;

            retryLock.unlock();

            try {
                assert !retryLock.isHeldByCurrentThread();
                retryTask.accept(thisAttemptNo);
            } finally {
                state.completeRetryTask();
            }

            completeRetryingIfNoPendingAttempts(ctx);
        }, retryTimeNanos, earliestRetryTimeNanos, cause -> {
            state.completeRetryTask();

            if (exceptionDuringScheduling.get()) {
                assert state(ctx).getLock().isHeldByCurrentThread();
                retryLock.unlock();
            }

            actionOnException.accept(cause);

            if (exceptionDuringScheduling.get()) {
                retryLock.lock();
            }
        });

        exceptionDuringScheduling.set(false);
        retryLock.unlock();
    }

    // todo(szymon): [doc]

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

    @FunctionalInterface
    protected interface AttemptWinHandler<O> {
        /**
         * todo(szymon): [doc].
         */
        void accept(ClientRequestContext attemptCtx, O attemptResOnComplete);
    }

    @FunctionalInterface
    protected interface AttemptAbortHandler<O> {
        /**
         * todo(szymon): [doc].
         */
        CompletableFuture<Void> accept(ClientRequestContext attemptCtx, O attemptRes,
                                       @Nullable Throwable abortCause);
    }

    /**
     * todo(szymon): [doc].
     */
    @VisibleForTesting
    @SuppressWarnings("unchecked")
    public static <O extends Response> State<O> state(ClientRequestContext ctx) {
        final State<O> state = (State<O>) ctx.attr(STATE);
        assert state != null;
        return state;
    }

    /**
     * todo(szymon): [doc].
     */
    @VisibleForTesting
    public static final class State<O extends Response> {
        /**
         * todo(szymon): [doc].
         */
        @VisibleForTesting
        public static class Attempt<O> {
            private final ClientRequestContext attemptCtx;
            private final O attemptRes;
            private @Nullable O attemptResOnComplete;

            private final AttemptWinHandler<O> onWinHandler;
            private final AttemptAbortHandler<O>
                    onAbortHandler;

            private boolean hasCalledHandler;

            Attempt(ClientRequestContext attemptCtx, O attemptRes,
                    AttemptWinHandler<O> onWinHandler,
                    AttemptAbortHandler<O> onAbortHandler) {
                this.attemptCtx = attemptCtx;
                this.attemptRes = attemptRes;
                this.onWinHandler = onWinHandler;
                this.onAbortHandler = onAbortHandler;

                hasCalledHandler = false;
                attemptResOnComplete = null;
            }

            ClientRequestContext ctx() {
                return attemptCtx;
            }

            /**
             * todo(szymon): [doc].
             */
            public O attemptRes() {
                return attemptRes;
            }

            void completeWithRes(O res) {
                assert attemptResOnComplete == null;
                attemptResOnComplete = res;
            }

            CompletableFuture<Void> callHandler(boolean isWinning, @Nullable Throwable cause) {
                if (hasCalledHandler) {
                    return UnmodifiableFuture.completedFuture(null);
                }

                hasCalledHandler = true;

                if (isWinning) {
                    assert attemptResOnComplete != null;
                    onWinHandler.accept(attemptCtx, attemptResOnComplete);
                    return UnmodifiableFuture.completedFuture(null);
                } else {
                    return onAbortHandler.accept(attemptCtx, attemptRes, cause);
                }
            }
        }

        private final ReentrantLock lock;
        private boolean isRetryingComplete;
        private final RetryConfig<?> config;
        private final long deadlineNanos;
        private final boolean isTimeoutEnabled;

        private final Map<ClientRequestContext, Attempt<O>> startedAttempts = new HashMap<>();

        private final RetryScheduler retryScheduler;

        private int numPendingAttempts;

        // An attempt is considered scheduled between two points:
        // - right before its retry task is scheduled
        // - right after the retry task finishes execution
        //
        // As a consequence, the retry task must call startAttempt() synchronously.
        // If it does not, we may assume there are no active or scheduled attempts
        // once the task ends, and stop retrying too early.
        private int numScheduledAttempts;

        private @Nullable Attempt<O> lastAttempt;

        @Nullable
        private Backoff lastBackoff;
        private int currentAttemptNoWithLastBackoff;
        // Starting with 1
        private int totalAttemptNo;

        State(ReentrantLock retryingLock, RetryConfig<?> config, RetryScheduler retryScheduler) {
            lock = retryingLock;
            isRetryingComplete = false;
            this.config = config;
            this.retryScheduler = retryScheduler;
            totalAttemptNo = 1;
            numPendingAttempts = 0;
            numScheduledAttempts = 0;

            deadlineNanos = 0;
            isTimeoutEnabled = false;
        }

        State(ReentrantLock retryingLock, RetryConfig<?> config, RetryScheduler retryScheduler,
              long responseTimeoutTimeNanos) {
            lock = retryingLock;
            isRetryingComplete = false;
            this.config = config;
            this.retryScheduler = retryScheduler;
            totalAttemptNo = 1;
            numPendingAttempts = 0;
            numScheduledAttempts = 0;

            deadlineNanos = responseTimeoutTimeNanos;
            isTimeoutEnabled = true;
        }

        RetryScheduler scheduler() {
            return retryScheduler;
        }

        ReentrantLock getLock() {
            return lock;
        }

        /**
         * Returns the smaller value between {@link RetryConfig#responseTimeoutMillisForEachAttempt()} and
         * remaining {@link #responseTimeoutMillisForAttempt}. This method is thread-safe.
         *
         * @return 0 if the response timeout for both of each request and whole retry is disabled or
         *         -1 if the elapsed time from the first request has passed {@code responseTimeoutMillis}
         */
        long responseTimeoutMillisForAttempt() {
            lock.lock();

            try {
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
            } finally {
                lock.unlock();
            }
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

        void startAttempt(ClientRequestContext attemptCtx, O attemptRes,
                          AttemptWinHandler<O> onWinHandler,
                          AttemptAbortHandler<O>
                                  onAbortHandler) {
            lock.lock();
            try {
                checkState(!isRetryingComplete());
                checkState(!startedAttempts.containsKey(attemptCtx),
                           "Attempt %s already is already pending.", attemptCtx);
                final Attempt<O> attempt = new Attempt<>(attemptCtx, attemptRes, onWinHandler,
                                                         onAbortHandler);
                startedAttempts.put(attempt.ctx(), attempt);
                numPendingAttempts++;
            } finally {
                lock.unlock();
            }
        }

        void completeAttempt(ClientRequestContext attemptCtx, O attemptRes, boolean isWinning) {
            lock.lock();
            try {
                if (isRetryingComplete()) {
                    lock.unlock();
                    return;
                }

                checkState(startedAttempts.containsKey(attemptCtx),
                           "Attempt %s is not registered as pending.", attemptCtx);

                lastAttempt = startedAttempts.get(attemptCtx);
                lastAttempt.completeWithRes(attemptRes);
                numPendingAttempts--;
            } catch (Exception e) {
                lock.unlock();
                throw e;
            }

            if (isWinning) {
                // If this is the winning attempt, we can complete the retrying.
                // transfers ownership of current lock
                complete0();
            } else {
                // transfers ownership of current lock
                completeIfNoPendingAttempts0();
            }
        }

        void startRetryTask() {
            // This can get (temporarily) above max total attempts as there is a moment between scheduling
            // a new retry task and cancelling the previous one. This is because startRetryTask() needs to be
            // increment before we call the RetryScheduler.schedule() method.
            lock.lock();
            numScheduledAttempts++;
            lock.unlock();
        }

        void completeRetryTask() {
            lock.lock();
            try {
                checkState(numScheduledAttempts > 0);
                numScheduledAttempts--;
            } finally {
                lock.unlock();
            }
        }

        int numAttemptsSoFarWithBackoff(Backoff backoff) {
            lock.lock();
            try {
                if (totalAttemptNo >= config.maxTotalAttempts()) {
                    return -1;
                }

                if (lastBackoff != backoff) {
                    return 1;
                }

                return currentAttemptNoWithLastBackoff;
            } finally {
                lock.unlock();
            }
        }

        int acquireAttemptNoWithCurrentBackoff(Backoff backoff) {
            lock.lock();
            try {
                checkState(!isRetryingComplete());
                checkState((totalAttemptNo + 1) <= config.maxTotalAttempts(),
                           "Exceeded the maximum number of attempts: %s", config.maxTotalAttempts());

                totalAttemptNo++;

                if (lastBackoff != backoff) {
                    lastBackoff = backoff;
                    currentAttemptNoWithLastBackoff = 1;
                }

                currentAttemptNoWithLastBackoff++;

                return totalAttemptNo;
            } finally {
                lock.unlock();
            }
        }

        int numPendingAttempts() {
            lock.lock();
            try {
                return numPendingAttempts + (numScheduledAttempts > 0 ? 1 : 0);
            } finally {
                lock.unlock();
            }
        }

        void completeIfNoPendingAttempts() {
            lock.lock();

            if (isRetryingComplete()) {
                lock.unlock();
                return;
            }

            // transfers ownership of current lock
            completeIfNoPendingAttempts0();
        }

        // transfers ownership of current lock
        private void completeIfNoPendingAttempts0() {
            assert lock.isHeldByCurrentThread();
            assert !isRetryingComplete();

            if (numPendingAttempts() > 0) {
                lock.unlock();
                return;
            }

            // transfer ownership of current lock
            complete0();
        }

        // takes ownership of current lock
        private void complete0() {
            assert lock.isHeldByCurrentThread();
            assert !isRetryingComplete();

            final boolean hasLastAttempt = lastAttempt != null;

            if (!hasLastAttempt) {
                // takes ownership of current lock
                completeExceptionally0(
                        new IllegalStateException("Completed retrying without any attempts."));
            } else {
                isRetryingComplete = true;
                scheduler().shutdown();
                lock.unlock();
                // We do not want to call the handlers with the lock.
                // Save to reference lastAttempt as this cannot change anymore.
                notifyAllAttemptHandlers(lastAttempt, null);
            }
        }

        void completeExceptionally(Throwable cause) {
            lock.lock();

            if (isRetryingComplete()) {
                lock.unlock();
                return;
            }

            // takes ownership of current lock
            completeExceptionally0(cause);
        }

        private void notifyAllAttemptHandlers(@Nullable Attempt<O> winningAttempt, @Nullable Throwable cause) {
            assert isRetryingComplete();

            final CompletableFuture<?> allOtherAttemptsAbortedFuture = CompletableFuture.allOf(
                    startedAttempts.values().stream()
                                   .filter(attempt -> attempt != winningAttempt)
                                   .map(attempt ->
                                                attempt.callHandler(false, cause)
                                   )
                                   .toArray(CompletableFuture[]::new)
            );

            allOtherAttemptsAbortedFuture.handle(
                    (unused, throwable) -> {
                        if (throwable != null) {
                            logger.warn("Failed to notify all aborted attempt handlers.", throwable);
                        }

                        if (winningAttempt != null) {
                            winningAttempt.callHandler(true, cause);
                        }
                        return null;
                    });
        }

        // takes ownership of current lock
        private void completeExceptionally0(Throwable cause) {
            assert lock.isHeldByCurrentThread();
            assert !isRetryingComplete();

            isRetryingComplete = true;
            scheduler().shutdown();

            // We do not want to call the handlers with the lock.
            lock.unlock();

            notifyAllAttemptHandlers(null, cause);
        }

        /**
         * todo(szymon): [doc].
         */
        public boolean isRetryingComplete() {
            try {
                lock.lock();
                return isRetryingComplete;
            } finally {
                lock.unlock();
            }
        }

        /**
         * todo(szymon): [doc].
         */
        public List<Attempt<O>> startedAttempts() {
            lock.lock();
            try {
                return ImmutableList.copyOf(startedAttempts.values());
            } finally {
                lock.unlock();
            }
        }
    }
}
