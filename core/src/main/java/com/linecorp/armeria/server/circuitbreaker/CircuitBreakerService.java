/*
 * Copyright 2020 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;

/**
 * Decorates an {@link HttpService} to circuit break the incoming requests.
 */
public final class CircuitBreakerService extends AbstractCircuitBreakerService<HttpRequest, HttpResponse>
        implements HttpService {
    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} and
     * {@link CircuitBreakerRejectHandler}.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to define the circuit breaker to be used.
     * @param rejectHandler The {@link CircuitBreakerRejectHandler} instance to define request rejection behaviour
     */
    public static Function<? super HttpService, CircuitBreakerService>
    newDecorator(CircuitBreaker circuitBreaker,
                 CircuitBreakerRejectHandler<HttpRequest, HttpResponse> rejectHandler) {
        return builder(circuitBreaker).onRejectedRequest(rejectHandler).newDecorator();
    }

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker}.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to define the circuit breaker to be used.
     */
    public static Function<? super HttpService, CircuitBreakerService>
    newDecorator(CircuitBreaker circuitBreaker) {
        return builder(circuitBreaker).newDecorator();
    }

    /**
     * Returns a new {@link CircuitBreakerServiceBuilder}.
     */
    public static CircuitBreakerServiceBuilder builder(CircuitBreaker circuitBreaker) {
        return new CircuitBreakerServiceBuilder(circuitBreaker);
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    CircuitBreakerService(HttpService delegate, CircuitBreaker circuitBreaker,
                          CircuitBreakerAcceptHandler<HttpRequest, HttpResponse> acceptHandler,
                          CircuitBreakerRejectHandler<HttpRequest, HttpResponse> rejectHandler) {
        super(delegate, circuitBreaker, HttpResponse::from, acceptHandler, rejectHandler);
    }
}
