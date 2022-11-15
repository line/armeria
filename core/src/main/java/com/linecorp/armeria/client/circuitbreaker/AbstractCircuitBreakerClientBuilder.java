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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
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
    private CircuitBreakerClientHandler<I> handler =
            CircuitBreakerClientHandler.of(CircuitBreakerMapping.ofDefault());

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRule}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerRule rule) {
        this(requireNonNull(rule, "rule"), null);
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRuleWithContent}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerRuleWithContent<O> ruleWithContent) {
        this(null, requireNonNull(ruleWithContent, "ruleWithContent"));
    }

    private AbstractCircuitBreakerClientBuilder(
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerRuleWithContent<O> ruleWithContent) {
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
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
    public AbstractCircuitBreakerClientBuilder<I, O> mapping(CircuitBreakerMapping mapping) {
        handler = CircuitBreakerClientHandler.of(requireNonNull(mapping, "mapping"));
        return this;
    }

    /**
     * Sets the {@link CircuitBreakerClientHandler}. Note that the {@link CircuitBreakerMapping}
     * set by calling {@link #mapping(CircuitBreakerMapping)} will be overwritten by calling this method.
     *
     * @return {@code this} to support method chaining.
     *
     * @see CircuitBreakerClientHandler
     */
    @UnstableApi
    public AbstractCircuitBreakerClientBuilder<I, O> handler(CircuitBreakerClientHandler<I> handler) {
        this.handler = requireNonNull(handler, "handler");
        return this;
    }

    final CircuitBreakerClientHandler<I> handler() {
        return handler;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("rule", rule)
                          .add("ruleWithContent", ruleWithContent)
                          .add("handler", handler)
                          .toString();
    }
}
