/*
 * Copyright 2016 LINE Corporation
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

/**
 * An exception indicating that a request has been failed by circuit breaker.
 */
public final class FailFastException extends RuntimeException {

    private static final long serialVersionUID = -946827349873835165L;

    private final CircuitBreaker circuitBreaker;

    FailFastException(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Returns the {@link CircuitBreaker} that has detected the failure.
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
