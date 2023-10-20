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

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a new {@link CircuitBreakerClient} or its decorator function.
 */
public final class CircuitBreakerClientBuilder
        extends AbstractCircuitBreakerClientBuilder<HttpRequest, HttpResponse> {
    static final int DEFAULT_MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

    private final boolean needsContentInRule;
    private final int maxContentLength;

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRule}.
     */
    CircuitBreakerClientBuilder(CircuitBreakerRule rule) {
        super(rule);
        needsContentInRule = false;
        maxContentLength = 0;
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRuleWithContent} and
     * the specified {@code maxContentLength}.
     */
    CircuitBreakerClientBuilder(CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
                                int maxContentLength) {
        super(ruleWithContent);
        needsContentInRule = true;
        this.maxContentLength = maxContentLength;
    }

    /**
     * Returns a newly-created {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public CircuitBreakerClient build(HttpClient delegate) {
        if (needsContentInRule) {
            return new CircuitBreakerClient(
                    delegate, handler(), ruleWithContent(), maxContentLength, fallback());
        }
        return new CircuitBreakerClient(delegate, handler(), rule(), fallback());
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

    @Override
    @UnstableApi
    public CircuitBreakerClientBuilder handler(CircuitBreakerClientHandler handler) {
        return (CircuitBreakerClientBuilder) super.handler(handler);
    }

    @Override
    public CircuitBreakerClientBuilder recover(BiFunction<? super ClientRequestContext, ? super HttpRequest,
            ? extends HttpResponse> fallback) {
        return (CircuitBreakerClientBuilder) super.recover(fallback);
    }
}
