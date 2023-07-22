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

import java.util.function.Function;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link CircuitBreakerService}.
 */
public final class CircuitBreakerServiceBuilder
        extends
        AbstractCircuitBreakerServiceBuilder<HttpRequest, HttpResponse> {

    /**
     * Provides default circuit breaking behaviour for {@link HttpRequest}.
     * Returns an {@link HttpResponse} with {@link HttpStatus#SERVICE_UNAVAILABLE}.
     */
    private static final CircuitBreakerRejectHandler<HttpRequest, HttpResponse>
            DEFAULT_REJECT_HANDLER =
            (delegate, ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);

    CircuitBreakerServiceBuilder(CircuitBreaker circuitBreaker) {
        super(circuitBreaker, DEFAULT_REJECT_HANDLER);
    }

    /**
     * Sets {@link CircuitBreakerAcceptHandler}.
     */
    public CircuitBreakerServiceBuilder onAcceptedRequest(
            CircuitBreakerAcceptHandler<HttpRequest, HttpResponse> acceptHandler) {
        setAcceptHandler(acceptHandler);
        return this;
    }

    /**
     * Sets {@link CircuitBreakerRejectHandler}.
     */
    public CircuitBreakerServiceBuilder onRejectedRequest(
            CircuitBreakerRejectHandler<HttpRequest, HttpResponse> rejectHandler) {
        setRejectHandler(rejectHandler);
        return this;
    }

    /**
     * Returns a newly-created {@link CircuitBreakerService} based on the {@link CircuitBreaker} added to
     * this builder.
     */
    public CircuitBreakerService build(HttpService delegate) {
        return new CircuitBreakerService(requireNonNull(delegate, "delegate"), getCircuitBreaker(),
                                         getAcceptHandler(), getRejectHandler());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link Service} with a new
     * {@link CircuitBreakerService} based on the {@link CircuitBreaker} added to this builder.
     */
    public Function<? super HttpService, CircuitBreakerService> newDecorator() {
        final CircuitBreaker circuitBreaker = getCircuitBreaker();
        final CircuitBreakerAcceptHandler<HttpRequest, HttpResponse> acceptHandler = getAcceptHandler();
        final CircuitBreakerRejectHandler<HttpRequest, HttpResponse> rejectHandler = getRejectHandler();
        return service ->
                new CircuitBreakerService(service, circuitBreaker, acceptHandler, rejectHandler);
    }
}
