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

/**
 * Failure detection and fallback mechanism based on
 * <a href="https://martinfowler.com/bliki/CircuitBreaker.html">circuit breaker pattern</a>.
 *
 * <h2>Setup Client with Circuit Breaker</h2>
 * <h2>Example</h2>
 * <pre>{@code
 * Iface helloClient =
 *     Clients.builder("tbinary+http://127.0.0.1:8080/hello")
 *            .decorator(CircuitBreakerClient.newDecorator(
 *                    CircuitBreaker.builder("hello").build()))
 *            .build(Iface.class);
 * }</pre>
 *
 * <h2>A Unit of Failure Detection</h2>
 * You can specify a unit of failure detection from the following:
 * <ul>
 *     <li>per remote service (as shown in the example above)</li>
 *     <li>per method</li>
 *     <li>per remote host</li>
 *     <li>per remote host and method</li>
 * </ul>
 * <h2>Example</h2>
 * <pre>{@code
 * // Setup with per-method failure detection
 * AsyncIface helloClient =
 *     Clients.builder("tbinary+http://127.0.0.1:8080/hello")
 *            .decorator(CircuitBreakerClient.newPerMethodDecorator(
 *                    method -> CircuitBreaker.builder(method).build()))
 *            .build(AsyncIface.class);
 * }</pre>
 *
 * <h2>Fallback</h2>
 * Once a failure is detected, A {@link com.linecorp.armeria.client.circuitbreaker.FailFastException} is thrown
 * from the client. You can write a fallback code by catching the exception.
 * <h2>Example</h2>
 * <pre>{@code
 * try {
 *     helloClient.hello("line");
 * } catch (TException e) {
 *     // error handling
 * } catch (FailFastException e) {
 *    // fallback code
 * }
 * }</pre>
 *
 * <h2>Example in Async Client</h2>
 * <pre>{@code
 * helloClient.hello("line", new AsyncMethodCallback() {
 *     public void onComplete(Object response) {
 *         // response handling
 *     }
 *     public void onError(Exception e) {
 *         if (e instanceof TException) {
 *             // error handling
 *         } else if (e instanceof FailFastException) {
 *             // fallback code
 *         }
 *     }
 * });
 * }</pre>
 *
 * <h2>Circuit States and Transitions</h2>
 * The circuit breaker provided by this package is implemented as a finite state machine consisting of the
 * following states and transitions.
 *
 * <h2>{@code CLOSED}</h2>
 * The initial state. All requests are sent to the remote service. If the failure rate exceeds
 * the specified threshold, the state turns into {@code OPEN}.
 *
 * <h2>{@code OPEN}</h2>
 * All requests fail immediately without calling the remote service. After the specified time,
 * the state turns into {@code HALF_OPEN}.
 *
 * <h2>{@code HALF_OPEN}</h2>
 * Only one trial request is sent at a time.
 * <ul>
 *     <li>If it succeeds, the state turns into {@code CLOSED}.</li>
 *     <li>If it fails, the state returns to {@code OPEN}.</li>
 *     <li>If it doesn't complete within a certain time,
 *     another trial request will be sent again.</li>
 * </ul>
 *
 * <h2>Circuit Breaker Configurations</h2>
 * The behavior of a circuit breaker can be modified via
 * {@link com.linecorp.armeria.client.circuitbreaker.CircuitBreakerBuilder}.
 *
 * <h2>{@code failureRateThreshold}</h2>
 * The threshold of failure rate(= failure/total) to detect a remote service fault.
 *
 * <h2>{@code minimumRequestThreshold}</h2>
 * The minimum number of requests within the time window necessary to detect a remote service
 * fault.
 * <h2>{@code circuitOpenWindow}</h2>
 * The duration of {@code OPEN} state.
 *
 * <h2>{@code trialRequestInterval}</h2>
 * The interval of trial request in {@code HALF_OPEN} state.
 *
 * <h2>{@code counterSlidingWindow}</h2>
 * The time length of sliding window to accumulate the count of events.
 *
 * <h2>{@code counterUpdateInterval}</h2>
 * The interval that a circuit breaker can see the latest count of events.
 *
 * <h2>{@code exceptionFilter}</h2>
 * A filter that decides whether a circuit breaker should deal with a given error.
 */
@NonNullByDefault
package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.common.annotation.NonNullByDefault;
