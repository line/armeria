/*
 * Copyright 2022 LINE Corporation
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
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation that builds a new {@link AbstractCircuitBreakerClient} or
 * its decorator function.
 *
 * @param <I> the type of incoming {@link Request} of the {@link Client}
 * @param <O> the type of incoming {@link Response} of the {@link Client}
 */
public abstract class AbstractCircuitBreakerClientBuilder<I extends Request, O extends Response> {

    @Nullable
    private final CircuitBreakerRule rule;
    @Nullable
    private final CircuitBreakerRuleWithContent<O> ruleWithContent;
    private CircuitBreakerClientHandler handler =
            CircuitBreakerClientHandler.of(CircuitBreakerMapping.ofDefault());
    @Nullable
    private BiFunction<? super ClientRequestContext, ? super I, ? extends O> fallback;

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRule}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerRule rule) {
        this(requireNonNull(rule, "rule"), null, null);
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRuleWithContent}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerRuleWithContent<O> ruleWithContent) {
        this(null, requireNonNull(ruleWithContent, "ruleWithContent"), null);
    }

    private AbstractCircuitBreakerClientBuilder(
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerRuleWithContent<O> ruleWithContent,
            @Nullable BiFunction<? super ClientRequestContext, I, O> fallback) {
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
        this.fallback = fallback;
    }

    final CircuitBreakerRule rule() {
        checkState(rule != null, "rule is not set.");
        return rule;
    }

    final CircuitBreakerRuleWithContent<O> ruleWithContent() {
        checkState(ruleWithContent != null, "ruleWithContent is not set.");
        return ruleWithContent;
    }

    /**
     * Sets the {@link CircuitBreakerMapping}. If unspecified, {@link CircuitBreakerMapping#ofDefault()}
     * will be used. Note that the {@link CircuitBreakerClientHandler} set by calling
     * {@link #handler(CircuitBreakerClientHandler)} will be overwritten by calling this method.
     *
     * @return {@code this} to support method chaining.
     */
    @UnstableApi
    public AbstractCircuitBreakerClientBuilder<I, O> mapping(CircuitBreakerMapping mapping) {
        handler = CircuitBreakerClientHandler.of(requireNonNull(mapping, "mapping"));
        return this;
    }

    /**
     * Sets the {@link CircuitBreakerClientHandler}. Note that the {@link CircuitBreakerMapping}
     * set by calling {@link #mapping(CircuitBreakerMapping)} will be overwritten by calling this method.
     *
     * @return {@code this} to support method chaining.
     */
    @UnstableApi
    public AbstractCircuitBreakerClientBuilder<I, O> handler(CircuitBreakerClientHandler handler) {
        this.handler = requireNonNull(handler, "handler");
        return this;
    }

    final CircuitBreakerClientHandler handler() {
        return handler;
    }

    @Nullable
    final BiFunction<? super ClientRequestContext, ? super I, ? extends O> fallback() {
        return fallback;
    }

    /**
     * Sets the {@link BiFunction}. This is invoked when adding the fallback strategy.
     * <p>For example:</p>
     * <pre>{@code
     * CircuitBreakerClient
     *   .builder(...)
     *   .recover((ctx, req) -> {
     *       // fallback logic
     *       return HttpResponse.of(...);
     *   });
     * }</pre>
     *
     * @return {@code this} to support method chaining.
     */
    @UnstableApi
    public AbstractCircuitBreakerClientBuilder<I, O> recover(
            BiFunction<? super ClientRequestContext, ? super I, ? extends O> fallback) {
        requireNonNull(fallback, "fallback");
        this.fallback = fallback;
        return this;
    }
}
