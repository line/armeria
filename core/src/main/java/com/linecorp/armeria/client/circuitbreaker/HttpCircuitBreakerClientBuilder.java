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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for a {@link HttpClient} decorator that handles failures of HTTP requests
 * based on circuit breaker pattern.
 */
@UnstableApi
public abstract class HttpCircuitBreakerClientBuilder<CB>
        extends AbstractCircuitBreakerClientBuilder<CB, HttpResponse> {
    static final int DEFAULT_MAX_CONTENT_LENGTH = Integer.MAX_VALUE;
    private final boolean needsContentInRule;
    private final int maxContentLength;

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRule}.
     */
    protected HttpCircuitBreakerClientBuilder(ClientCircuitBreakerGenerator<CB> defaultMapping,
                                              CircuitBreakerRule rule) {
        super(defaultMapping, requireNonNull(rule, "rule"));
        needsContentInRule = false;
        maxContentLength = 0;
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRuleWithContent} and
     * the specified {@code maxContentLength}.
     */
    protected HttpCircuitBreakerClientBuilder(ClientCircuitBreakerGenerator<CB> defaultMapping,
                                              CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
                                              int maxContentLength) {
        super(defaultMapping, requireNonNull(ruleWithContent, "ruleWithContent"));
        needsContentInRule = true;
        this.maxContentLength = maxContentLength;
    }

    /**
     * Returns the {@code maxContentLength} which is required to determine a {@link Response}
     * as a success or failure. This value is valid only if {@link CircuitBreakerRuleWithContent} is used.
     */
    protected final int maxContentLength() {
        return maxContentLength;
    }

    /**
     * Returns a newly-created {@link CircuitBreakerClient} based on the properties of this builder.
     */
    protected CircuitBreakerClient build(HttpClient delegate,
                                         CircuitBreakerHandlerFactory<CB, HttpRequest> factory) {
        if (needsContentInRule) {
            return new CircuitBreakerClient(delegate, mapping(), ruleWithContent(), maxContentLength, factory);
        }
        return new CircuitBreakerClient(delegate, mapping(), rule(), factory);
    }

    /**
     * Returns a newly-created {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public abstract CircuitBreakerClient build(HttpClient delegate);

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public abstract Function<? super HttpClient, CircuitBreakerClient> newDecorator();

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, CircuitBreakerClient> newDecorator(
            CircuitBreakerHandlerFactory<CB, HttpRequest> factory) {
        return delegate -> build(delegate, factory);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("needsContentInRule", needsContentInRule)
                          .add("maxContentLength", maxContentLength)
                          .toString();
    }
}
