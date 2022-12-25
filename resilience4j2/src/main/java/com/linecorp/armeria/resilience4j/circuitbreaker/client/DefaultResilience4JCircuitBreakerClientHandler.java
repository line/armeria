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

package com.linecorp.armeria.resilience4j.circuitbreaker.client;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientHandler;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

final class DefaultResilience4JCircuitBreakerClientHandler<T extends Request>
        implements CircuitBreakerClientHandler<T> {

    private static final Logger logger =
            LoggerFactory.getLogger(DefaultResilience4JCircuitBreakerClientHandler.class);

    private final ClientCircuitBreakerGenerator<CircuitBreaker> mapping;

    DefaultResilience4JCircuitBreakerClientHandler(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        this.mapping = mapping;
    }

    @Override
    public CircuitBreakerCallback tryRequest(ClientRequestContext ctx, T req) {
        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = requireNonNull(mapping.get(ctx, req), "circuitBreaker");
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return null;
        }
        circuitBreaker.acquirePermission();
        final long startTimestamp = circuitBreaker.getCurrentTimestamp();
        return new Resilience4JCircuitBreakerCallback(circuitBreaker, startTimestamp);
    }
}
