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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NonBlockingCircuitBreakerTest {

    private static final String remoteServiceName = "testService";

    private static final AtomicLong ticker = new AtomicLong();

    private static final Duration circuitOpenWindow = Duration.ofSeconds(1);

    private static final Duration trialRequestInterval = Duration.ofSeconds(1);

    private static final Duration counterUpdateInterval = Duration.ofSeconds(1);

    private static final CircuitBreakerListener listener = mock(CircuitBreakerListener.class);

    private static NonBlockingCircuitBreaker create(long minimumRequestThreshold, double failureRateThreshold) {
        return (NonBlockingCircuitBreaker) CircuitBreaker.builder(remoteServiceName)
                                                         .failureRateThreshold(failureRateThreshold)
                                                         .minimumRequestThreshold(minimumRequestThreshold)
                                                         .circuitOpenWindow(circuitOpenWindow)
                                                         .trialRequestInterval(trialRequestInterval)
                                                         .counterSlidingWindow(Duration.ofSeconds(10))
                                                         .counterUpdateInterval(counterUpdateInterval)
                                                         .listener(listener)
                                                         .ticker(ticker::get)
                                                         .build();
    }

    private static NonBlockingCircuitBreaker closedState(long minimumRequestThreshold, double failureRateThreshold) {
        final NonBlockingCircuitBreaker cb = create(minimumRequestThreshold, failureRateThreshold);
        assertThat(cb.state().isClosed()).isTrue();
        assertThat(cb.canRequest()).isTrue();
        return cb;
    }

    private static NonBlockingCircuitBreaker openState(long minimumRequestThreshold,
                                                       double failureRateThreshold) {
        final NonBlockingCircuitBreaker cb = create(minimumRequestThreshold, failureRateThreshold);
        cb.onSuccess();
        cb.onFailure();
        cb.onFailure();
        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();
        assertThat(cb.state().isOpen()).isTrue();
        assertThat(cb.canRequest()).isFalse();
        return cb;
    }

    private static NonBlockingCircuitBreaker halfOpenState(long minimumRequestThreshold,
                                                           double failureRateThreshold) {
        final NonBlockingCircuitBreaker cb = openState(minimumRequestThreshold, failureRateThreshold);

        ticker.addAndGet(circuitOpenWindow.toNanos());

        assertThat(cb.state().isHalfOpen()).isFalse();
        assertThat(cb.canRequest()).isTrue(); // first request is allowed
        assertThat(cb.state().isHalfOpen()).isTrue();
        assertThat(cb.canRequest()).isFalse(); // seconds request is refused
        return cb;
    }

    private static Stream<Arguments> circuitStatePairArguments() {
        return Stream.of(CircuitState.values()).flatMap(
                state1 -> Stream.of(CircuitState.values())
                                .map(state2 -> Arguments.of(state1, state2)));
    }

    @Test
    void testClosed() {
        closedState(2, 0.5);
    }

    @Test
    void testMinimumRequestThreshold() {
        final NonBlockingCircuitBreaker cb = create(4, 0.5);
        assertThat(cb.state().isClosed() && cb.canRequest()).isTrue();

        cb.onFailure();
        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();
        assertThat(cb.state().isClosed()).isTrue();
        assertThat(cb.canRequest()).isTrue();

        cb.onFailure();
        cb.onFailure();
        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isOpen()).isTrue();
        assertThat(cb.canRequest()).isFalse();
    }

    @Test
    void testFailureRateThreshold() {
        final NonBlockingCircuitBreaker cb = create(10, 0.5);

        for (int i = 0; i < 10; i++) {
            cb.onSuccess();
        }
        for (int i = 0; i < 9; i++) {
            cb.onFailure();
        }

        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isClosed()).isTrue(); // 10 vs 9 (0.47)
        assertThat(cb.canRequest()).isTrue();

        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isClosed()).isTrue(); // 10 vs 10 (0.5)
        assertThat(cb.canRequest()).isTrue();

        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();

        assertThat(cb.state().isOpen()).isTrue(); // 10 vs 11 (0.52)
        assertThat(cb.canRequest()).isFalse();
    }

    @Test
    void testClosedToOpen() {
        openState(2, 0.5);
    }

    @Test
    void testOpenToHalfOpen() {
        halfOpenState(2, 0.5);
    }

    @Test
    void testHalfOpenToClosed() {
        final NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        cb.onSuccess();

        assertThat(cb.state().isClosed()).isTrue();
        assertThat(cb.canRequest()).isTrue();
    }

    @Test
    void testHalfOpenToOpen() {
        final NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        cb.onFailure();

        assertThat(cb.state().isOpen()).isTrue();
        assertThat(cb.canRequest()).isFalse();
    }

    @Test
    void testHalfOpenRetryRequest() {
        final NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        ticker.addAndGet(trialRequestInterval.toNanos());

        assertThat(cb.state().isHalfOpen()).isTrue();
        assertThat(cb.canRequest()).isTrue(); // first request is allowed
        assertThat(cb.state().isHalfOpen()).isTrue();
        assertThat(cb.canRequest()).isFalse(); // seconds request is refused
    }

    @Test
    void testClosedOpenClosed() {
        final NonBlockingCircuitBreaker cb = closedState(2, 0.5);

        cb.onFailure();
        cb.onFailure();

        ticker.addAndGet(counterUpdateInterval.toNanos());
        assertThat(cb.canRequest()).isTrue();
        cb.onFailure();
        assertThat(cb.state().isOpen()).isTrue();

        assertThat(cb.canRequest()).isFalse();
        ticker.addAndGet(trialRequestInterval.toNanos());

        assertThat(cb.canRequest()).isTrue();
        cb.onSuccess();
        assertThat(cb.state().isClosed()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("circuitStatePairArguments")
    void testTransitionOpenToClosed(CircuitState from, CircuitState to) throws Exception {
        final NonBlockingCircuitBreaker cb = create(2, 0.5);
        final String name = cb.name();

        CircuitState prevState = cb.state().circuitState();
        reset(listener);
        assertThat(cb.transitionTo(from)).isEqualTo(from != prevState);
        assertThat(cb.state().circuitState()).isEqualTo(from);
        if (prevState != cb.state().circuitState()) {
            verify(listener).onEventCountUpdated(name, EventCount.ZERO);
            verify(listener).onStateChanged(name, from);
        } else {
            verify(listener, never()).onEventCountUpdated(name, EventCount.ZERO);
            verify(listener, never()).onStateChanged(name, from);
        }

        prevState = cb.state().circuitState();
        reset(listener);
        assertThat(cb.transitionTo(to)).isEqualTo(to != prevState);
        assertThat(cb.state().circuitState()).isEqualTo(to);
        if (prevState != cb.state().circuitState()) {
            verify(listener).onEventCountUpdated(name, EventCount.ZERO);
            verify(listener).onStateChanged(name, to);
        } else {
            verify(listener, never()).onEventCountUpdated(name, EventCount.ZERO);
            verify(listener, never()).onStateChanged(name, to);
        }
    }

    @Test
    void testClosedCounterResetOnTransition() throws Exception {
        final NonBlockingCircuitBreaker cb = create(2, 0.5);
        final String name = cb.name();

        reset(listener);
        cb.onFailure();
        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();
        assertThat(cb.state().isClosed()).isTrue();
        verify(listener, times(1)).onEventCountUpdated(name, EventCount.of(0, 1));
        reset(listener);

        // transition closed -> open -> closed
        assertThat(cb.transitionTo(CircuitState.OPEN)).isTrue();
        assertThat(cb.transitionTo(CircuitState.CLOSED)).isTrue();

        // verify counter is reset
        reset(listener);
        cb.onFailure();
        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();
        assertThat(cb.state().isClosed()).isTrue();
        verify(listener, times(1)).onEventCountUpdated(name, EventCount.of(0, 1));
        reset(listener);
    }

    @Test
    void testTimeoutResetOnTransition() throws Exception {
        final NonBlockingCircuitBreaker cb = openState(2, 0.5);
        final String name = cb.name();

        reset(listener);
        cb.onFailure();
        assertThat(cb.state().isOpen()).isTrue();
        ticker.addAndGet(circuitOpenWindow.toNanos() - 1);
        assertThat(cb.canRequest()).isFalse();

        // transition open -> closed -> open
        assertThat(cb.transitionTo(CircuitState.CLOSED)).isTrue();
        assertThat(cb.transitionTo(CircuitState.OPEN)).isTrue();

        // verify window is reset
        cb.onFailure();
        assertThat(cb.state().isOpen()).isTrue();
        ticker.addAndGet(circuitOpenWindow.toNanos() - 1);
        assertThat(cb.canRequest()).isFalse();
    }

    @Test
    void testNotification() throws Exception {
        reset(listener);

        final NonBlockingCircuitBreaker cb = create(4, 0.5);
        final String name = cb.name();

        // Notify initial state
        verify(listener, times(1)).onEventCountUpdated(name, EventCount.ZERO);
        verify(listener, times(1)).onInitialized(name, CircuitState.CLOSED);
        reset(listener);

        cb.onFailure();
        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();

        // Notify updated event count
        verify(listener, times(1)).onEventCountUpdated(name, EventCount.of(0, 1));
        reset(listener);

        // Notify circuit tripped

        cb.onFailure();
        cb.onFailure();
        ticker.addAndGet(counterUpdateInterval.toNanos());
        cb.onFailure();

        verify(listener, times(1)).onEventCountUpdated(name, EventCount.ZERO);
        verify(listener, times(1)).onStateChanged(name, CircuitState.OPEN);
        reset(listener);

        // Notify request rejected

        cb.canRequest();
        verify(listener, times(1)).onRequestRejected(name);

        ticker.addAndGet(circuitOpenWindow.toNanos());

        // Notify half open

        cb.canRequest();

        verify(listener, times(1)).onEventCountUpdated(name, EventCount.ZERO);
        verify(listener, times(1)).onStateChanged(name, CircuitState.HALF_OPEN);
        reset(listener);

        // Notify circuit closed

        cb.onSuccess();

        verify(listener, times(1)).onEventCountUpdated(name, EventCount.ZERO);
        verify(listener, times(1)).onStateChanged(name, CircuitState.CLOSED);
    }
}
