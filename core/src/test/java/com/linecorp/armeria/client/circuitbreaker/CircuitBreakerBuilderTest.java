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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class CircuitBreakerBuilderTest {

    private static final String remoteServiceName = "testService";

    private static final Duration minusDuration = Duration.ZERO.minusMillis(1);

    private static final Duration oneSecond = Duration.ofSeconds(1);

    private static final Duration twoSeconds = Duration.ofSeconds(2);

    private static CircuitBreakerBuilder builder() {
        return CircuitBreaker.builder(remoteServiceName);
    }

    @Test
    void testConstructor() {
        assertThat(CircuitBreaker.builder(remoteServiceName).build().name()).isEqualTo(remoteServiceName);
        assertThat(CircuitBreaker.builder().build().name()).startsWith("circuit-breaker-");
    }

    @Test
    void testConstructorWithInvalidArgument() {
        assertThatThrownBy(() -> CircuitBreaker.builder(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CircuitBreaker.builder(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    CircuitBreakerConfig confOf(CircuitBreaker circuitBreaker) {
        return ((NonBlockingCircuitBreaker) circuitBreaker).config();
    }

    @Test
    void testFailureRateThreshold() {
        assertThat(confOf(builder().failureRateThreshold(0.123).build()).failureRateThreshold())
                .isEqualTo(0.123);
        assertThat(confOf(builder().failureRateThreshold(1).build()).failureRateThreshold())
                .isEqualTo(1.0);
    }

    @Test
    void testFailureRateThresholdWithInvalidArgument() {
        assertThatThrownBy(() -> builder().failureRateThreshold(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().failureRateThreshold(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().failureRateThreshold(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testMinimumRequestThreshold() {
        final CircuitBreakerConfig config1 = confOf(builder().minimumRequestThreshold(Long.MAX_VALUE).build());
        assertThat(config1.minimumRequestThreshold()).isEqualTo(Long.MAX_VALUE);

        final CircuitBreakerConfig config2 = confOf(builder().minimumRequestThreshold(0).build());
        assertThat(config2.minimumRequestThreshold()).isEqualTo(0L);
    }

    @Test
    void testMinimumRequestThresholdWithInvalidArgument() {
        assertThatThrownBy(() -> builder().minimumRequestThreshold(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testTrialRequestInterval() {
        final CircuitBreakerConfig config = confOf(builder().trialRequestInterval(oneSecond).build());
        assertThat(config.trialRequestInterval()).isEqualTo(oneSecond);
    }

    @Test
    void testTrialRequestIntervalInMillis() {
        final CircuitBreakerConfig config = confOf(
                builder().trialRequestIntervalMillis(oneSecond.toMillis()).build());
        assertThat(config.trialRequestInterval()).isEqualTo(oneSecond);
    }

    @Test
    void testTrialRequestIntervalWithInvalidArgument() {
        assertThatThrownBy(() -> builder().trialRequestInterval(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().trialRequestInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().trialRequestInterval(minusDuration))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().trialRequestIntervalMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().trialRequestIntervalMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testCircuitOpenWindow() {
        final CircuitBreakerConfig config = confOf(builder().circuitOpenWindow(oneSecond).build());
        assertThat(config.circuitOpenWindow()).isEqualTo(oneSecond);
    }

    @Test
    void testCircuitOpenWindowInMillis() {
        final CircuitBreakerConfig config =
                confOf(builder().circuitOpenWindowMillis(oneSecond.toMillis()).build());
        assertThat(config.circuitOpenWindow()).isEqualTo(oneSecond);
    }

    @Test
    void testCircuitOpenWindowWithInvalidArgument() {
        assertThatThrownBy(() -> builder().circuitOpenWindow(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().circuitOpenWindow(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().circuitOpenWindow(minusDuration))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().circuitOpenWindowMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().circuitOpenWindowMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testCounterSlidingWindow() {
        final CircuitBreakerConfig config = confOf(builder().counterSlidingWindow(twoSeconds).build());
        assertThat(config.counterSlidingWindow()).isEqualTo(twoSeconds);
    }

    @Test
    void testCounterSlidingWindowInMillis() {
        final CircuitBreakerConfig config = confOf(
                builder().counterSlidingWindowMillis(twoSeconds.toMillis()).build());
        assertThat(config.counterSlidingWindow()).isEqualTo(twoSeconds);
    }

    @Test
    void testCounterSlidingWindowWithInvalidArgument() {
        assertThatThrownBy(() -> builder().counterSlidingWindow(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().counterSlidingWindow(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().counterSlidingWindow(minusDuration))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().counterSlidingWindowMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().counterSlidingWindowMillis(0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> builder().counterSlidingWindow(oneSecond).counterUpdateInterval(twoSeconds)
                                          .build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testCounterUpdateInterval() {
        final CircuitBreakerConfig config = confOf(builder().counterUpdateInterval(oneSecond).build());
        assertThat(config.counterUpdateInterval()).isEqualTo(oneSecond);
    }

    @Test
    void testCounterUpdateIntervalInMillis() {
        final CircuitBreakerConfig config = confOf(
                builder().counterUpdateIntervalMillis(oneSecond.toMillis()).build());
        assertThat(config.counterUpdateInterval()).isEqualTo(oneSecond);
    }

    @Test
    void testCounterUpdateIntervalWithInvalidArgument() {
        assertThatThrownBy(() -> builder().counterUpdateInterval(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().counterUpdateInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().counterUpdateInterval(minusDuration))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().counterUpdateIntervalMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().counterUpdateIntervalMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
