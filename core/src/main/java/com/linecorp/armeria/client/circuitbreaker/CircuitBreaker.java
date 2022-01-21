/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

/**
 * A <a href="https://martinfowler.com/bliki/CircuitBreaker.html">circuit breaker</a>, which tracks the number of
 * success/failure requests and detects a remote service failure.
 */
public interface CircuitBreaker {

    /**
     * Returns a new {@link CircuitBreakerBuilder}.
     */
    static CircuitBreakerBuilder builder() {
        return new CircuitBreakerBuilder();
    }

    /**
     * Returns a new {@link CircuitBreakerBuilder} that has the specified name.
     *
     * @param name the name of the circuit breaker.
     */
    static CircuitBreakerBuilder builder(String name) {
        return new CircuitBreakerBuilder(name);
    }

    /**
     * Creates a new {@link CircuitBreaker} that has the specified name and the default configurations.
     *
     * @param name the name of the circuit breaker
     */
    static CircuitBreaker of(String name) {
        return builder(name).build();
    }

    /**
     * Creates a new {@link CircuitBreaker} that has a default name and the default configurations.
     */
    static CircuitBreaker ofDefaultName() {
        return builder().build();
    }

    /**
     * Returns the name of the circuit breaker.
     */
    String name();

    /**
     * Reports a remote invocation success.
     */
    void onSuccess();

    /**
     * Reports a remote invocation failure.
     */
    void onFailure();

    /**
     * Decides whether a request should be sent or failed depending on the current circuit state.
     *
     * @deprecated Use {@link #tryRequest()}.
     */
    @Deprecated
    boolean canRequest();

    /**
     * Decides whether a request should be sent or failed depending on the current circuit state.
     * If the current state is {@link CircuitState#OPEN} and {@link CircuitBreakerBuilder#circuitOpenWindow}
     * has passed, the state will enter {@link CircuitState#HALF_OPEN}.
     */
    default boolean tryRequest() {
        return canRequest();
    }

    /**
     * Returns the current {@link CircuitState}.
     */
    CircuitState circuitState();
}
