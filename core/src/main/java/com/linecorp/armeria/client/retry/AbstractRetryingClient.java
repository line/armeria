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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.ClientUtil;

import io.netty.util.concurrent.ScheduledFuture;

/**
 * A {@link Client} decorator that handles failures of remote invocation and retries requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
abstract class AbstractRetryingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRetryingClient.class);

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
        return doExecute(ctx, req, config);
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
    protected abstract O doExecute(ClientRequestContext ctx, I req, RetryConfig<O> config) throws Exception;

    /**
     * This should be called when retrying is finished.
     */
    protected static void onRetryingComplete(ClientRequestContext ctx) {
        ctx.logBuilder().endResponseWithLastChild();
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
     * Schedules next retry.
     */
    protected static void scheduleNextRetry(ClientRequestContext ctx,
                                            Consumer<? super Throwable> actionOnException,
                                            Runnable retryTask, long nextDelayMillis) {
        try {
            if (nextDelayMillis == 0) {
                ctx.eventLoop().execute(retryTask);
            } else {
                @SuppressWarnings("unchecked")
                final ScheduledFuture<Void> scheduledFuture = (ScheduledFuture<Void>) ctx
                        .eventLoop().schedule(retryTask, nextDelayMillis, TimeUnit.MILLISECONDS);
                scheduledFuture.addListener(future -> {
                    if (future.isCancelled()) {
                        // future is cancelled when the client factory is closed.
                        actionOnException.accept(new IllegalStateException(
                                ClientFactory.class.getSimpleName() + " has been closed."));
                    } else if (future.cause() != null) {
                        // Other unexpected exceptions.
                        actionOnException.accept(future.cause());
                    }
                });
            }
        } catch (Throwable t) {
            actionOnException.accept(t);
        }
    }

    /**
     * Returns the next delay which retry will be made after. The delay will be:
     *
     * <p>{@code Math.min(responseTimeoutMillis, Math.max(Backoff.nextDelayMillis(int),
     * millisAfterFromServer))}
     *
     * @return the number of milliseconds to wait for before attempting a retry. -1 if the
     *         {@code currentAttemptNo} exceeds the {@code maxAttempts} or the {@code nextDelay} is after
     *         the moment which timeout happens.
     */
    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final long getNextDelay(RetryContext<I, O> rctx, Backoff backoff, long millisAfterFromServer) {
        if (rctx.counter().hasReachedMaxAttempts()) {
            logger.debug("Exceeded the default number of max attempt: {}", rctx.config().maxTotalAttempts());
            return -1;
        }

        rctx.counter().consumeAttemptFrom(backoff);
        long nextDelay = backoff.nextDelayMillis(rctx.counter().attemptsSoFarWithBackoff(backoff));
        if (nextDelay < 0) {
            logger.debug("Exceeded the number of max attempts in the backoff: {}", backoff);
            return -1;
        }

        nextDelay = Math.max(nextDelay, millisAfterFromServer);
        if (rctx.timeoutForWholeRetryEnabled() && nextDelay > rctx.actualResponseTimeoutMillis()) {
            // The nextDelay will be after the moment which timeout will happen. So return just -1.
            return -1;
        }

        return nextDelay;
    }

    /**
     * Creates a new derived {@link ClientRequestContext}, replacing the requests.
     * If {@link ClientRequestContext#endpointGroup()} exists, a new {@link Endpoint} will be selected.
     */
    protected static ClientRequestContext newDerivedContext(ClientRequestContext ctx,
                                                            @Nullable HttpRequest req,
                                                            @Nullable RpcRequest rpcReq,
                                                            boolean initialAttempt) {
        return ClientUtil.newDerivedContext(ctx, req, rpcReq, initialAttempt);
    }
}
