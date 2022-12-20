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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientHandler;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * A {@link CircuitBreakerClientHandler} implementation for use with Resilience4j's {@link CircuitBreaker}.
 */
public final class Resilience4JCircuitBreakerClientHandler implements CircuitBreakerClientHandler<HttpRequest> {

    private static final Logger logger =
            LoggerFactory.getLogger(Resilience4JCircuitBreakerClientHandler.class);

    public static CircuitBreakerClientHandler<HttpRequest> of() {
        return new Resilience4JCircuitBreakerClientHandler(Resilience4jCircuitBreakerMapping.ofDefault());
    }

    public static CircuitBreakerClientHandler<HttpRequest> of(CircuitBreaker circuitBreaker) {
        return of((ctx, req) -> circuitBreaker);
    }

    public static CircuitBreakerClientHandler<HttpRequest> of(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
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
