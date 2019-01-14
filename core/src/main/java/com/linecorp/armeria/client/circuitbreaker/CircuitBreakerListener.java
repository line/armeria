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
 * The listener interface for receiving {@link CircuitBreaker} events.
 */
public interface CircuitBreakerListener {

    /**
     * Invoked when the circuit state is changed.
     */
    void onStateChanged(String circuitBreakerName, CircuitState state) throws Exception;

    /**
     * Invoked when the circuit state is changed.
     *
     * @deprecated Use {@link #onStateChanged(String, CircuitState)}.
     */
    @Deprecated
    default void onStateChanged(CircuitBreaker circuitBreaker, CircuitState state) throws Exception {
        onStateChanged(circuitBreaker.name(), state);
    }

    /**
     * Invoked when the circuit breaker's internal {@link EventCount} is updated.
     */
    void onEventCountUpdated(String circuitBreakerName, EventCount eventCount) throws Exception;

    /**
     * Invoked when the circuit breaker's internal {@link EventCount} is updated.
     *
     * @deprecated Use {@link #onEventCountUpdated(String, EventCount)}.
     */
    @Deprecated
    default void onEventCountUpdated(CircuitBreaker circuitBreaker, EventCount eventCount) throws Exception {
        onEventCountUpdated(circuitBreaker.name(), eventCount);
    }

    /**
     * Invoked when the circuit breaker rejects a request.
     */
    void onRequestRejected(String circuitBreakerName) throws Exception;

    /**
     * Invoked when the circuit breaker rejects a request.
     *
     * @deprecated Use {@link #onRequestRejected(String)}.
     */
    @Deprecated
    default void onRequestRejected(CircuitBreaker circuitBreaker) throws Exception {
        onRequestRejected(circuitBreaker.name());
    }
}
