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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.function.Function;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Response;

/**
 * Builds a new {@link CircuitBreakerClient} or its decorator function.
 */
public final class CircuitBreakerClientBuilder extends AbstractCircuitBreakerClientBuilder<HttpResponse> {
    static final int DEFAULT_MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

    private final boolean needsContentInRule;
    private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRule}.
     */
    CircuitBreakerClientBuilder(CircuitBreakerRule rule) {
        super(rule);
        needsContentInRule = false;
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRuleWithContent}.
     */
    CircuitBreakerClientBuilder(CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        super(ruleWithContent);
        needsContentInRule = true;
    }

    /**
     * Sets the length of content required to determine a {@link Response} as a success or failure.
     * Note that this property is useful only if you specified a {@link CircuitBreakerRuleWithContent} when
     * calling this builder's constructor. The default value of this property is
     * {@value #DEFAULT_MAX_CONTENT_LENGTH}.
     *
     * @param maxContentLength the maximum allowed content length. {@code 0} does not disable the length limit.
     *
     * @throws IllegalStateException if this builder is created with a {@link CircuitBreakerRule} rather than
     *                               {@link CircuitBreakerRuleWithContent}
     * @throws IllegalArgumentException if the specified {@code maxContentLength} is equal to or
     *                                  less than {@code 0}
     */
    public CircuitBreakerClientBuilder maxContentLength(int maxContentLength) {
        checkState(needsContentInRule, "cannot set maxContentLength when CircuitBreakerRule " +
                                       "is used; Use CircuitBreakerRuleWithContent to enable this feature.");
        checkArgument(maxContentLength > 0,
                      "maxContentLength: %s (expected: > 0)", maxContentLength);
        this.maxContentLength = maxContentLength;
        return this;
    }

    /**
     * Returns a newly-created {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public CircuitBreakerClient build(HttpClient delegate) {
        if (needsContentInRule) {
            return new CircuitBreakerClient(delegate, mapping(), ruleWithContent(), maxContentLength);
        }

        return new CircuitBreakerClient(delegate, mapping(), rule());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, CircuitBreakerClient> newDecorator() {
        return this::build;
    }

    // Methods that were overridden to change the return type.

    @Override
    public CircuitBreakerClientBuilder mapping(CircuitBreakerMapping mapping) {
        return (CircuitBreakerClientBuilder) super.mapping(mapping);
    }
}
