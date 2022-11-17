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

package com.linecorp.armeria.resilience4j.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientHandler;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * A {@link CircuitBreakerClientHandler} implementation for use with Resilience4j's {@link CircuitBreaker}.
 */
public final class Resilience4JCircuitBreakerClientHandler implements CircuitBreakerClientHandler<HttpRequest> {

    private static final Logger logger =
            LoggerFactory.getLogger(Resilience4JCircuitBreakerClientHandler.class);

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerRule rule) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return newDecorator((ctx, req) -> circuitBreaker, rule);
    }

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return newDecorator((ctx, req) -> circuitBreaker, ruleWithContent);
    }

    /**
     * Creates a new decorator with the specified {@link Resilience4jCircuitBreakerMapping} and
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreakerRule rule) {
        requireNonNull(rule, "rule");
        return newDecorator(Resilience4jCircuitBreakerMapping.ofDefault(), rule);
    }

    /**
     * Creates a new decorator with the specified {@link Resilience4jCircuitBreakerMapping} and
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(Resilience4jCircuitBreakerMapping mapping, CircuitBreakerRule rule) {
        requireNonNull(mapping, "mapping");
        requireNonNull(rule, "rule");
        return CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(mapping), rule);
    }

    /**
     * Creates a new decorator with the specified {@link Resilience4jCircuitBreakerMapping} and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        requireNonNull(ruleWithContent, "ruleWithContent");
        return newDecorator(Resilience4jCircuitBreakerMapping.ofDefault(), ruleWithContent);
    }

    /**
     * Creates a new decorator with the specified {@link Resilience4jCircuitBreakerMapping} and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, CircuitBreakerClient>
    newDecorator(Resilience4jCircuitBreakerMapping mapping,
                 CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        requireNonNull(mapping, "mapping");
        requireNonNull(ruleWithContent, "ruleWithContent");
        return CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(mapping),
                                                 ruleWithContent);
    }

    static CircuitBreakerClientHandler<HttpRequest> of(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        return new Resilience4JCircuitBreakerClientHandler(mapping);
    }

    private final ClientCircuitBreakerGenerator<CircuitBreaker> mapping;

    Resilience4JCircuitBreakerClientHandler(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        this.mapping = mapping;
    }

    @Override
    public CircuitBreakerCallback tryRequest(ClientRequestContext ctx, HttpRequest req) {
        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = mapping.get(ctx, req);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return null;
        }
        circuitBreaker.acquirePermission();
        final long startTimestamp = circuitBreaker.getCurrentTimestamp();
        return new Resilience4JCircuitBreakerCallback(circuitBreaker, startTimestamp);
    }
}
