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
 * @param <CB> the type of CircuitBreaker implementation
 * @param <O> the type of incoming {@link Response} of the {@link Client}
 */
@UnstableApi
public abstract class AbstractCircuitBreakerClientBuilder<CB, I extends Request, O extends Response> {

    @Nullable
    private final CircuitBreakerRuleWithContent<O> ruleWithContent;
    @Nullable
    private final CircuitBreakerRule rule;
    private ClientCircuitBreakerGenerator<CB> mapping;
    private CircuitBreakerClientHandlerFactory<CB, I> factory;

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRule}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerClientHandlerFactory<CB, I> defaultFactory,
                                        ClientCircuitBreakerGenerator<CB> defaultMapping,
                                        CircuitBreakerRule rule) {
        this(defaultFactory, defaultMapping, requireNonNull(rule, "rule"), null);
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRuleWithContent}.
     */
    AbstractCircuitBreakerClientBuilder(CircuitBreakerClientHandlerFactory<CB, I> defaultFactory,
                                        ClientCircuitBreakerGenerator<CB> defaultMapping,
                                        CircuitBreakerRuleWithContent<O> ruleWithContent) {
        this(defaultFactory, defaultMapping, null, requireNonNull(ruleWithContent, "ruleWithContent"));
    }

    private AbstractCircuitBreakerClientBuilder(
            CircuitBreakerClientHandlerFactory<CB, I> defaultFactory,
            ClientCircuitBreakerGenerator<CB> defaultMapping,
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerRuleWithContent<O> ruleWithContent) {
        factory = requireNonNull(defaultFactory, "defaultFactory");
        mapping = requireNonNull(defaultMapping, "defaultMapping");
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
    }

    /**
     * Sets the {@link ClientCircuitBreakerGenerator} which generates a {@link CB} instance
     * for each request.
     */
    public AbstractCircuitBreakerClientBuilder<CB, I, O> mapping(ClientCircuitBreakerGenerator<CB> mapping) {
        this.mapping = requireNonNull(mapping, "mapping");
        return this;
    }

    /**
     * Returns the set {@link ClientCircuitBreakerGenerator}.
     */
    protected ClientCircuitBreakerGenerator<CB> mapping() {
        return mapping;
    }

    /**
     * Sets the {@link CircuitBreakerClientHandlerFactory} which generates the
     * {@link CircuitBreakerClientHandler} for this client.
     */
    public AbstractCircuitBreakerClientBuilder<CB, I, O> factory(
            CircuitBreakerClientHandlerFactory<CB, I> factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Returns the {@link CircuitBreakerClientHandlerFactory} which generates the
     * {@link CircuitBreakerClientHandler} for this client.
     */
    protected CircuitBreakerClientHandlerFactory<CB, I> factory() {
        return factory;
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
                          .add("mapping", mapping)
                          .add("factory", factory)
                          .toString();
    }
}
