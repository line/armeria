/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.common.metric.Gauge;
import com.linecorp.armeria.common.metric.MetricGroup;

/**
 * A {@link MetricGroup} of {@link CircuitBreaker} stats.
 */
public interface CircuitBreakerMetrics extends MetricGroup {

    /**
     * Returns the number of requests in the counter time window.
     */
    Gauge total();

    /**
     * Returns the number of successful requests in the counter time window.
     */
    Gauge success();

    /**
     * Returns the number of failed requests in the counter time window.
     */
    Gauge failure();

    /**
     * Returns the number of circuit breaker state transitions to {@link CircuitState#CLOSED}.
     */
    Gauge transitionToClosed();

    /**
     * Returns the number of circuit breaker state transitions to {@link CircuitState#OPEN}.
     */
    Gauge transitionToOpen();

    /**
     * Returns the number of circuit breaker state transitions to {@link CircuitState#HALF_OPEN}.
     */
    Gauge transitionToHalfOpen();

    /**
     * Returns the number of requests rejected by the circuit breaker.
     */
    Gauge rejectedRequest();
}
