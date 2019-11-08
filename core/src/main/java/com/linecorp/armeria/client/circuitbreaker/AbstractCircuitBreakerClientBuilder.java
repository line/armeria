/*
 * Copyright 2018 LINE Corporation
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

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Response;

/**
 * A skeletal builder implementation that builds a new {@link CircuitBreakerClient} or its decorator function.
 *
 * @param <O> the type of incoming {@link Response} of the {@link Client}
 */
public abstract class AbstractCircuitBreakerClientBuilder<O extends Response> {

    @Nullable
    private final CircuitBreakerStrategy strategy;

    @Nullable
    private final CircuitBreakerStrategyWithContent<O> strategyWithContent;

    private CircuitBreakerMapping mapping = CircuitBreakerMapping.ofDefault();

    /**
     * Creates a new builder with the specified {@link CircuitBreakerStrategy}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerStrategy strategy) {
        this(requireNonNull(strategy, "strategy"), null);
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerStrategyWithContent}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerStrategyWithContent<O> strategyWithContent) {
        this(null, requireNonNull(strategyWithContent, "strategyWithContent"));
    }

    private AbstractCircuitBreakerClientBuilder(
            @Nullable CircuitBreakerStrategy strategy,
            @Nullable CircuitBreakerStrategyWithContent<O> strategyWithContent) {
        this.strategy = strategy;
        this.strategyWithContent = strategyWithContent;
    }

    CircuitBreakerStrategy strategy() {
        checkState(strategy != null, "strategy is not set.");
        return strategy;
    }

    CircuitBreakerStrategyWithContent<O> strategyWithContent() {
        checkState(strategyWithContent != null, "strategyWithContent is not set.");
        return strategyWithContent;
    }

    /**
     * Sets the {@link CircuitBreakerMapping}. If unspecified, {@link CircuitBreakerMapping#ofDefault()}
     * will be used.
     *
     * @return {@code this} to support method chaining.
     *
     * @deprecated Use {@link #mapping(CircuitBreakerMapping)}.
     */
    @Deprecated
    public AbstractCircuitBreakerClientBuilder<O> circuitBreakerMapping(CircuitBreakerMapping mapping) {
        return mapping(mapping);
    }

    /**
     * Sets the {@link CircuitBreakerMapping}. If unspecified, {@link CircuitBreakerMapping#ofDefault()}
     * will be used.
     *
     * @return {@code this} to support method chaining.
     */
    public AbstractCircuitBreakerClientBuilder<O> mapping(CircuitBreakerMapping mapping) {
        this.mapping = requireNonNull(mapping, "mapping");
        return this;
    }

    CircuitBreakerMapping mapping() {
        return mapping;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("strategy", strategy)
                          .add("strategyWithContent", strategyWithContent)
                          .add("mapping", mapping)
                          .toString();
    }
}
