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
@UnstableApi
public abstract class AbstractCircuitBreakerClientBuilder<I extends Request, O extends Response> {

    @Nullable
    private final CircuitBreakerRuleWithContent<O> ruleWithContent;
    @Nullable
    private final CircuitBreakerRule rule;
    private ClientCircuitBreakerHandler<I> handler =
            DefaultClientCircuitBreakerHandler.of(CircuitBreakerMapping.ofDefault());

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

    /**
     * Sets the {@link CircuitBreakerMapping} which generates a {@link CircuitBreaker} instance
     * for each request.
     */
    public AbstractCircuitBreakerClientBuilder<I, O> mapping(CircuitBreakerMapping mapping) {
        handler = DefaultClientCircuitBreakerHandler.of(requireNonNull(mapping, "mapping"));
        return this;
    }

    /**
     * Sets the {@link ClientCircuitBreakerHandler} which generates the
     * {@link ClientCircuitBreakerHandler} for this client.
     */
    public AbstractCircuitBreakerClientBuilder<I, O> handler(ClientCircuitBreakerHandler<I> handler) {
        this.handler = requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Returns the {@link ClientCircuitBreakerHandler} for this client.
     */
    protected ClientCircuitBreakerHandler<I> handler() {
        return handler;
    }

    /**
     * Returns the {@link CircuitBreakerRule} set for this builder.
     */
    protected final CircuitBreakerRule rule() {
        checkState(rule != null, "rule is not set.");
        return rule;
    }

    /**
     * Returns the {@link CircuitBreakerRuleWithContent} set for this builder.
     */
    protected final CircuitBreakerRuleWithContent<O> ruleWithContent() {
        checkState(ruleWithContent != null, "ruleWithContent is not set.");
        return ruleWithContent;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("ruleWithContent", ruleWithContent)
                          .add("rule", rule)
                          .add("factory", handler)
                          .toString();
    }
}
