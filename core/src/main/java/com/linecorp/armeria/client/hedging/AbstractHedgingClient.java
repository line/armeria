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
package com.linecorp.armeria.client.hedging;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
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
import io.netty.util.concurrent.ScheduledFuture;

public abstract class AbstractHedgingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractHedgingClient.class);

    public static final AsciiString ARMERIA_RETRY_COUNT = HttpHeaderNames.of("armeria-retry-count");

    private static final AttributeKey<State> STATE =
            AttributeKey.valueOf(AbstractHedgingClient.class, "STATE");

    private final HedgingConfigMapping<O> mapping;

    @Nullable
    private final HedgingConfig<O> hedgingConfig;

    AbstractHedgingClient(
            Client<I, O> delegate, HedgingConfigMapping<O> mapping, @Nullable HedgingConfig<O> hedgingConfig) {
        super(delegate);
        this.mapping = requireNonNull(mapping, "mapping");
        this.hedgingConfig = hedgingConfig;
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final HedgingConfig<O> config = mapping.get(ctx, req);
        requireNonNull(config, "mapping.get() returned null");

        final State state = new State(config, ctx.responseTimeoutMillis());
        ctx.setAttr(STATE, state);
        return doExecute(ctx, req);
    }

    protected final HedgingConfigMapping<O> mapping() {
        return mapping;
    }
    
    protected abstract O doExecute(ClientRequestContext ctx, I req) throws Exception;

    protected final HedgingRule hedgingRule() {
        checkState(hedgingConfig != null, "No hedgingRule set. Are you using HedgingConfigMapping?");
        final HedgingRule hedgingRule = hedgingConfig.hedgingRule();
        checkState(hedgingRule != null, "hedgingRule is not set.");
        return hedgingRule;
    }

    final HedgingConfig<O> mappedHedgingConfig(ClientRequestContext ctx) {
        @SuppressWarnings("unchecked")
        final HedgingConfig<O> config = (HedgingConfig<O>) state(ctx).config;
        return config;
    }

    protected final HedgingRuleWithContent<O> hedgingRuleWithContent() {
        checkState(hedgingConfig != null, "No hedgingRuleWithContent set. Are you using HedgingConfigMapping?");
        final HedgingRuleWithContent<O> hedgingRuleWithContent = hedgingConfig.hedgingRuleWithContent();
        checkState(hedgingRuleWithContent != null, "hedgingRuleWithContent is not set.");
        return hedgingRuleWithContent;
    }

    protected static void scheduleNextHedge(ClientRequestContext ctx,
                                            Consumer<? super Throwable> actionOnException,
                                            Runnable hedgingTask, long nextHedgingDelayMillis) {
        try {
            if (nextHedgingDelayMillis == 0) {
                ctx.eventLoop().execute(hedgingTask);
            } else {
                @SuppressWarnings("unchecked")
                final ScheduledFuture<Void> scheduledFuture = (ScheduledFuture<Void>) ctx
                        .eventLoop().schedule(hedgingTask, nextHedgingDelayMillis, TimeUnit.MILLISECONDS);
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

    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final boolean setResponseTimeout(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        final long responseTimeoutMillis = state(ctx).responseTimeoutMillis();
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

    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final long getNextDelay(ClientRequestContext ctx, long hedgingDelayMillis) {
        requireNonNull(ctx, "ctx");

        final State state = state(ctx);
        final int currentAttemptNo = ++state.totalAttemptNo;

        if (currentAttemptNo > state.config.maxTotalAttempts()) {
            logger.debug("Exceeded the default number of max attempt: {}", state.config.maxTotalAttempts());
            return HedgingDecision.NO_HEDGING_DELAY_MILLIS;
        }

        if (hedgingDelayMillis < 0) {
            logger.debug("hedgingDelayMillis is negative: {}", hedgingDelayMillis);
            return HedgingDecision.NO_HEDGING_DELAY_MILLIS;
        }

        final long nextHedgingDelay = hedgingDelayMillis; // Math.max(hedgingDelayMillis,
        // millisAfterFromServer);
        if (state.timeoutForWholeHedgeEnabled() && nextHedgingDelay > state.actualResponseTimeoutMillis()) {
            // The nextDelay will be after the moment which timeout will happen. So return just NO_HEDGING_DELAY_MILLIS.
            return HedgingDecision.NO_HEDGING_DELAY_MILLIS;
        }

        return nextHedgingDelay;
    }

    protected static int getTotalAttempts(ClientRequestContext ctx) {
        final State state = ctx.attr(STATE);
        if (state == null) {
            return 0;
        }
        return state.totalAttemptNo;
    }

    protected static ClientRequestContext newDerivedContext(ClientRequestContext ctx,
                                                            @Nullable HttpRequest req,
                                                            @Nullable RpcRequest rpcReq,
                                                            boolean initialAttempt) {
        return ClientUtil.newDerivedContext(ctx, req, rpcReq, initialAttempt);
    }

    protected static void onHedgingComplete(ClientRequestContext ctx) {
        ctx.logBuilder().endResponseWithLastChild();
    }

    private static State state(ClientRequestContext ctx) {
        final State state = ctx.attr(STATE);
        assert state != null;
        return state;
    }

    private static final class State {

        private final HedgingConfig<?> config;
        private final long deadlineNanos;
        private final boolean isTimeoutEnabled;

        private final long hedgingDelayMillis;
        private int totalAttemptNo;

        State(HedgingConfig<?> config, long responseTimeoutMillis) {
            this.config = config;

            if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
                deadlineNanos = 0;
                isTimeoutEnabled = false;
            } else {
                deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
                isTimeoutEnabled = true;
            }

            hedgingDelayMillis = config.initialHedgingDelayMillis();
        }

        long responseTimeoutMillis() {
            if (!timeoutForWholeHedgeEnabled()) {
                return config.responseTimeoutMillisForEachAttempt();
            }

            final long actualResponseTimeoutMillis = actualResponseTimeoutMillis();

            // Consider 0 or less than 0 of actualResponseTimeoutMillis as timed out.
            if (actualResponseTimeoutMillis <= 0) {
                return -1;
            }

            if (config.responseTimeoutMillisForEachAttempt() > 0) {
                return Math.min(config.responseTimeoutMillisForEachAttempt(), actualResponseTimeoutMillis);
            }

            return actualResponseTimeoutMillis;
        }

        boolean timeoutForWholeHedgeEnabled() {
            return isTimeoutEnabled;
        }

        long actualResponseTimeoutMillis() {
            return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        }
    }
}
