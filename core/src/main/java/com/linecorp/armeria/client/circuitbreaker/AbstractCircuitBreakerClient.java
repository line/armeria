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

package com.linecorp.armeria.client.circuitbreaker;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.fromCircuitBreakerRuleWithContent;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A {@link Client} decorator that handles failures of remote invocation based on circuit breaker pattern.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractCircuitBreakerClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    @Nullable
    private final CircuitBreakerRule rule;

    @Nullable
    private final CircuitBreakerRule fromRuleWithContent;

    @Nullable
    private final CircuitBreakerRuleWithContent<O> ruleWithContent;
    @Nullable
    private final BiFunction<? super ClientRequestContext, ? super I, ? extends O> fallback;
    private final CircuitBreakerClientHandler handler;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractCircuitBreakerClient(
            Client<I, O> delegate, CircuitBreakerClientHandler handler,
            CircuitBreakerRule rule,
            @Nullable BiFunction<? super ClientRequestContext, ? super I, ? extends O> fallback) {
        this(delegate, handler, requireNonNull(rule, "rule"), null, fallback);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractCircuitBreakerClient(
            Client<I, O> delegate, CircuitBreakerClientHandler handler,
            CircuitBreakerRuleWithContent<O> ruleWithContent,
            @Nullable BiFunction<? super ClientRequestContext, ? super I, ? extends O> fallback) {
        this(delegate, handler, null, requireNonNull(ruleWithContent, "ruleWithContent"), fallback);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    private AbstractCircuitBreakerClient(
            Client<I, O> delegate, CircuitBreakerClientHandler handler,
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerRuleWithContent<O> ruleWithContent,
            @Nullable BiFunction<? super ClientRequestContext, ? super I, ? extends O> fallback) {
        super(delegate);
        this.handler = requireNonNull(handler, "handler");
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
        if (ruleWithContent != null) {
            fromRuleWithContent = fromCircuitBreakerRuleWithContent(ruleWithContent);
        } else {
            fromRuleWithContent = null;
        }
        this.fallback = fallback;
    }

    /**
     * Returns the {@link CircuitBreakerRule}.
     *
     * @throws IllegalStateException if the {@link CircuitBreakerRule} is not set
     */
    final CircuitBreakerRule rule() {
        checkState(rule != null, "rule is not set.");
        return rule;
    }

    /**
     * Returns the {@link CircuitBreakerRuleWithContent}.
     *
     * @throws IllegalStateException if the {@link CircuitBreakerRuleWithContent} is not set
     */
    final CircuitBreakerRuleWithContent<O> ruleWithContent() {
        checkState(ruleWithContent != null, "ruleWithContent is not set.");
        return ruleWithContent;
    }

    /**
     * Returns the {@link CircuitBreakerRule} derived from {@link #ruleWithContent()}.
     *
     * @throws IllegalStateException if the {@link CircuitBreakerRuleWithContent} is not set
     */
    final CircuitBreakerRule fromRuleWithContent() {
        checkState(fromRuleWithContent != null, "fromRuleWithContent is not set.");
        return fromRuleWithContent;
    }

    final CircuitBreakerClientHandler handler() {
        return handler;
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        try {
            final CircuitBreakerCallback callback = handler.tryRequest(ctx, req);
            if (callback == null) {
                return unwrap().execute(ctx, req);
            }
            return doExecute(ctx, req, callback);
        } catch (Exception ex) {
            if (fallback != null && handler().isCircuitBreakerException(ex)) {
                final O res = fallback.apply(ctx, req);
                return requireNonNull(res, "fallback.apply() returned null.");
            }
            throw ex;
        }
    }

    /**
     * Invoked when the {@link CircuitBreaker} is in closed state.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req,
                                   CircuitBreakerCallback callback) throws Exception;

    /**
     * Reports a success or a failure to the specified {@link CircuitBreaker} according to the completed value
     * of the specified {@code future}. If the completed value is {@link CircuitBreakerDecision#ignore()},
     * this doesn't do anything.
     */
    static void reportSuccessOrFailure(CircuitBreakerCallback callback,
                                       CompletionStage<@Nullable CircuitBreakerDecision> future,
                                       ClientRequestContext ctx, @Nullable Throwable throwable) {
        future.handle((decision, unused) -> {
            if (decision != null) {
                if (decision == CircuitBreakerDecision.success() || decision == CircuitBreakerDecision.next()) {
                    callback.onSuccess(ctx);
                } else if (decision == CircuitBreakerDecision.failure()) {
                    callback.onFailure(ctx, throwable);
                } else {
                    // Ignore, does not count as a success nor failure.
                }
            }
            return null;
        }).exceptionally(CompletionActions::log);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("rule", rule)
                          .add("fromRuleWithContent", fromRuleWithContent)
                          .add("ruleWithContent", ruleWithContent)
                          .add("handler", handler)
                          .toString();
    }
}
