/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.server.circuitbreaker;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link AbstractCircuitBreakerService}.
 * @param <I> type of the request
 * @param <O> type of the response
 */
abstract class AbstractCircuitBreakerServiceBuilder<I extends Request, O extends Response> {

    private final CircuitBreaker circuitBreaker;
    private CircuitBreakerAcceptHandler<I, O> acceptHandler;
    private CircuitBreakerRejectHandler<I, O> rejectHandler;

    AbstractCircuitBreakerServiceBuilder(CircuitBreaker circuitBreaker,
                                         CircuitBreakerRejectHandler<I, O> defaultRejectHandler) {
        this.circuitBreaker = requireNonNull(circuitBreaker, "circuitBreaker");
        acceptHandler = Service::serve;
        rejectHandler = requireNonNull(defaultRejectHandler, "defaultRejectHandler");
    }

    final CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Sets {@link CircuitBreakerAcceptHandler}.
     */
    final void setAcceptHandler(
            CircuitBreakerAcceptHandler<I, O> acceptHandler) {
        this.acceptHandler = requireNonNull(acceptHandler, "acceptHandler");
    }

    final CircuitBreakerAcceptHandler<I, O> getAcceptHandler() {
        return acceptHandler;
    }

    /**
     * Sets {@link CircuitBreakerRejectHandler}.
     */
    final void setRejectHandler(
            CircuitBreakerRejectHandler<I, O> rejectHandler) {
        this.rejectHandler = requireNonNull(rejectHandler, "rejectHandler");
    }

    final CircuitBreakerRejectHandler<I, O> getRejectHandler() {
        return rejectHandler;
    }
}
