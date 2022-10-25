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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;

final class DefaultClientCircuitBreakerHandler<I extends Request> implements ClientCircuitBreakerHandler<I> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultClientCircuitBreakerHandler.class);

    static <I extends Request> ClientCircuitBreakerHandler<I> of(CircuitBreaker cb) {
        return of((ctx, req) -> cb);
    }

    static <I extends Request> DefaultClientCircuitBreakerHandler<I> of(CircuitBreakerMapping cb) {
        return new DefaultClientCircuitBreakerHandler<>(cb);
    }

    private final ClientCircuitBreakerGenerator<CircuitBreaker> mapping;

    private DefaultClientCircuitBreakerHandler(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        this.mapping = mapping;
    }

    @Override
    public CircuitBreakerClientCallbacks tryAcquireAndRequest(ClientRequestContext ctx,
                                                              I req) throws Exception {
        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = mapping.get(ctx, req);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return null;
        }
        if (!circuitBreaker.tryRequest()) {
            throw new FailFastException(circuitBreaker);
        }
        return new DefaultCircuitBreakerClientCallbacks(circuitBreaker);
    }
}
