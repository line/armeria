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
import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultClientCircuitBreakerHandler<I extends Request> implements ClientCircuitBreakerHandler<I> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultClientCircuitBreakerHandler.class);

    private final ClientCircuitBreakerGenerator<CircuitBreaker> mapping;
    @Nullable
    CircuitBreaker circuitBreaker;

    DefaultClientCircuitBreakerHandler(ClientCircuitBreakerGenerator<CircuitBreaker> mapping) {
        this.mapping = mapping;
    }

    @Override
    public void tryAcquireAndRequest(ClientRequestContext ctx, I req) {
        try {
            circuitBreaker = mapping.get(ctx, req);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            throw new CircuitBreakerAbortException(t);
        }
        if (!circuitBreaker.tryRequest()) {
            throw new FailFastException(circuitBreaker);
        }
    }

    private CircuitBreaker circuitBreaker() {
        if (circuitBreaker == null) {
            throw new IllegalStateException("Attempting to report to a null CircuitBreaker");
        }
        return circuitBreaker;
    }

    @Override
    public void onSuccess(ClientRequestContext ctx) {
        circuitBreaker().onSuccess();
    }

    @Override
    public void onFailure(ClientRequestContext ctx, @Nullable Throwable throwable) {
        circuitBreaker().onFailure();
    }
}
