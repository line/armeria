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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import com.google.common.testing.FakeTicker;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.testing.internal.AnticipatedException;

public class NonBlockingCircuitBreakerTest {

    private static final String remoteServiceName = "testService";

    private static final FakeTicker ticker = new FakeTicker();

    private static final Duration circuitOpenWindow = Duration.ofSeconds(1);

    private static final Duration trialRequestInterval = Duration.ofSeconds(1);

    private static final Duration counterUpdateInterval = Duration.ofSeconds(1);

    private static final CircuitBreakerListener listener = mock(CircuitBreakerListener.class);

    private static NonBlockingCircuitBreaker create(long minimumRequestThreshold, double failureRateThreshold) {
        return (NonBlockingCircuitBreaker) new CircuitBreakerBuilder(remoteServiceName)
                .failureRateThreshold(failureRateThreshold)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .trialRequestInterval(trialRequestInterval)
                .counterSlidingWindow(Duration.ofSeconds(10))
                .counterUpdateInterval(counterUpdateInterval)
                .listener(listener)
                .ticker(ticker)
                .build();
    }

    private static CircuitBreaker closedState(long minimumRequestThreshold, double failureRateThreshold) {
        NonBlockingCircuitBreaker cb = create(minimumRequestThreshold, failureRateThreshold);
        Assertions.assertThat(cb.state().isClosed()).isTrue();
        Assertions.assertThat(cb.canRequest()).isTrue();
        return cb;
    }

    private static NonBlockingCircuitBreaker openState(long minimumRequestThreshold,
                                                       double failureRateThreshold) {
        NonBlockingCircuitBreaker cb = create(minimumRequestThreshold, failureRateThreshold);
        cb.onSuccess();
        cb.onFailure();
        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();
        Assertions.assertThat(cb.state().isOpen()).isTrue();
        Assertions.assertThat(cb.canRequest()).isFalse();
        return cb;
    }

    private static NonBlockingCircuitBreaker halfOpenState(long minimumRequestThreshold,
                                                           double failureRateThreshold) {
        NonBlockingCircuitBreaker cb = openState(minimumRequestThreshold, failureRateThreshold);

        ticker.advance(circuitOpenWindow.toNanos());

        Assertions.assertThat(cb.state().isHalfOpen()).isFalse();
        Assertions.assertThat(cb.canRequest()).isTrue(); // first request is allowed
        Assertions.assertThat(cb.state().isHalfOpen()).isTrue();
        Assertions.assertThat(cb.canRequest()).isFalse(); // seconds request is refused
        return cb;
    }

    @Test
    public void testClosed() {
        closedState(2, 0.5);
    }

    @Test
    public void testMinimumRequestThreshold() {
        NonBlockingCircuitBreaker cb = create(4, 0.5);
        Assertions.assertThat(cb.state().isClosed() && cb.canRequest()).isTrue();

        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();
        Assertions.assertThat(cb.state().isClosed()).isTrue();
        Assertions.assertThat(cb.canRequest()).isTrue();

        cb.onFailure();
        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        Assertions.assertThat(cb.state().isOpen()).isTrue();
        Assertions.assertThat(cb.canRequest()).isFalse();
    }

    @Test
    public void testFailureRateThreshold() {
        NonBlockingCircuitBreaker cb = create(10, 0.5);

        for (int i = 0; i < 10; i++) {
            cb.onSuccess();
        }
        for (int i = 0; i < 9; i++) {
            cb.onFailure();
        }

        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        Assertions.assertThat(cb.state().isClosed()).isTrue(); // 10 vs 9 (0.47)
        Assertions.assertThat(cb.canRequest()).isTrue();

        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        Assertions.assertThat(cb.state().isClosed()).isTrue(); // 10 vs 10 (0.5)
        Assertions.assertThat(cb.canRequest()).isTrue();

        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        Assertions.assertThat(cb.state().isOpen()).isTrue(); // 10 vs 11 (0.52)
        Assertions.assertThat(cb.canRequest()).isFalse();
    }

    @Test
    public void testClosedToOpen() {
        openState(2, 0.5);
    }

    @Test
    public void testOpenToHalfOpen() {
        halfOpenState(2, 0.5);
    }

    @Test
    public void testHalfOpenToClosed() {
        NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        cb.onSuccess();

        Assertions.assertThat(cb.state().isClosed()).isTrue();
        Assertions.assertThat(cb.canRequest()).isTrue();
    }

    @Test
    public void testHalfOpenToOpen() {
        NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        cb.onFailure();

        Assertions.assertThat(cb.state().isOpen()).isTrue();
        Assertions.assertThat(cb.canRequest()).isFalse();
    }

    @Test
    public void testHalfOpenRetryRequest() {
        NonBlockingCircuitBreaker cb = halfOpenState(2, 0.5);

        ticker.advance(trialRequestInterval.toNanos());

        Assertions.assertThat(cb.state().isHalfOpen()).isTrue();
        Assertions.assertThat(cb.canRequest()).isTrue(); // first request is allowed
        Assertions.assertThat(cb.state().isHalfOpen()).isTrue();
        Assertions.assertThat(cb.canRequest()).isFalse(); // seconds request is refused
    }

    @Test
    public void testFailureOfExceptionFilter() {
        NonBlockingCircuitBreaker cb = (NonBlockingCircuitBreaker) new CircuitBreakerBuilder()
                .exceptionFilter(cause -> {
                    throw Exceptions.clearTrace(new AnticipatedException("exception filter failed"));
                })
                .ticker(ticker)
                .build();
        cb.onFailure(new Exception());
    }

    @Test
    public void testNotification() throws Exception {
        reset(listener);

        NonBlockingCircuitBreaker cb = create(4, 0.5);

        // Notify initial state
        verify(listener, times(1)).onEventCountUpdated(cb, EventCount.ZERO);
        verify(listener, times(1)).onStateChanged(cb, CircuitState.CLOSED);
        reset(listener);

        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        // Notify updated event count
        verify(listener, times(1)).onEventCountUpdated(cb, new EventCount(0, 1));
        reset(listener);

        // Notify circuit tripped

        cb.onFailure();
        cb.onFailure();
        ticker.advance(counterUpdateInterval.toNanos());
        cb.onFailure();

        verify(listener, times(1)).onEventCountUpdated(cb, EventCount.ZERO);
        verify(listener, times(1)).onStateChanged(cb, CircuitState.OPEN);
        reset(listener);

        // Notify request rejected

        cb.canRequest();
        verify(listener, times(1)).onRequestRejected(cb);

        ticker.advance(circuitOpenWindow.toNanos());

        // Notify half open

        cb.canRequest();

        verify(listener, times(1)).onEventCountUpdated(cb, EventCount.ZERO);
        verify(listener, times(1)).onStateChanged(cb, CircuitState.HALF_OPEN);
        reset(listener);

        // Notify circuit closed

        cb.onSuccess();

        verify(listener, times(1)).onEventCountUpdated(cb, EventCount.ZERO);
        verify(listener, times(1)).onStateChanged(cb, CircuitState.CLOSED);
    }
}
