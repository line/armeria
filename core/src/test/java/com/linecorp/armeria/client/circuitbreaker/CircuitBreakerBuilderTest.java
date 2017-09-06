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
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.fail;

import java.time.Duration;

import org.junit.Test;

public class CircuitBreakerBuilderTest {

    private static final String remoteServiceName = "testService";

    private static final Duration minusDuration = Duration.ZERO.minusMillis(1);

    private static final Duration oneSecond = Duration.ofSeconds(1);

    private static final Duration twoSeconds = Duration.ofSeconds(2);

    private static void throwsException(Runnable runnable) {
        try {
            runnable.run();
            fail();
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            // Expected
        }
    }

    private static CircuitBreakerBuilder builder() {
        return new CircuitBreakerBuilder(remoteServiceName);
    }

    @Test
    public void testConstructor() {
        assertThat(new CircuitBreakerBuilder(remoteServiceName).build().name(), is(remoteServiceName));
        assertThat(new CircuitBreakerBuilder().build().name(), is(startsWith("circuit-breaker-")));
    }

    @Test
    public void testConstructorWithInvalidArgument() {
        throwsException(() -> new CircuitBreakerBuilder(null));
        throwsException(() -> new CircuitBreakerBuilder(""));
    }

    CircuitBreakerConfig confOf(CircuitBreaker circuitBreaker) {
        return ((NonBlockingCircuitBreaker) circuitBreaker).config();
    }

    @Test
    public void testFailureRateThreshold() {
        assertThat(confOf(builder().failureRateThreshold(0.123).build()).failureRateThreshold(), is(0.123));
        assertThat(confOf(builder().failureRateThreshold(1).build()).failureRateThreshold(), is(1.0));
    }

    @Test
    public void testFailureRateThresholdWithInvalidArgument() {
        throwsException(() -> builder().failureRateThreshold(0));
        throwsException(() -> builder().failureRateThreshold(-1));
        throwsException(() -> builder().failureRateThreshold(1.1));
    }

    @Test
    public void testMinimumRequestThreshold() {
        CircuitBreakerConfig config1 = confOf(builder().minimumRequestThreshold(Long.MAX_VALUE).build());
        assertThat(config1.minimumRequestThreshold(), is(Long.MAX_VALUE));

        CircuitBreakerConfig config2 = confOf(builder().minimumRequestThreshold(0).build());
        assertThat(config2.minimumRequestThreshold(), is(0L));
    }

    @Test
    public void testMinimumRequestThresholdWithInvalidArgument() {
        throwsException(() -> builder().minimumRequestThreshold(-1));
    }

    @Test
    public void testTrialRequestInterval() {
        CircuitBreakerConfig config = confOf(builder().trialRequestInterval(oneSecond).build());
        assertThat(config.trialRequestInterval(), is(oneSecond));
    }

    @Test
    public void testTrialRequestIntervalInMillis() {
        CircuitBreakerConfig config = confOf(
                builder().trialRequestIntervalMillis(oneSecond.toMillis()).build());
        assertThat(config.trialRequestInterval(), is(oneSecond));
    }

    @Test
    public void testTrialRequestIntervalWithInvalidArgument() {
        throwsException(() -> builder().trialRequestInterval(null));
        throwsException(() -> builder().trialRequestInterval(Duration.ZERO));
        throwsException(() -> builder().trialRequestInterval(minusDuration));
        throwsException(() -> builder().trialRequestIntervalMillis(-1));
        throwsException(() -> builder().trialRequestIntervalMillis(0));
    }

    @Test
    public void testCircuitOpenWindow() {
        CircuitBreakerConfig config = confOf(builder().circuitOpenWindow(oneSecond).build());
        assertThat(config.circuitOpenWindow(), is(oneSecond));
    }

    @Test
    public void testCircuitOpenWindowInMillis() {
        CircuitBreakerConfig config = confOf(builder().circuitOpenWindowMillis(oneSecond.toMillis()).build());
        assertThat(config.circuitOpenWindow(), is(oneSecond));
    }

    @Test
    public void testCircuitOpenWindowWithInvalidArgument() {
        throwsException(() -> builder().circuitOpenWindow(null));
        throwsException(() -> builder().circuitOpenWindow(Duration.ZERO));
        throwsException(() -> builder().circuitOpenWindow(minusDuration));
        throwsException(() -> builder().circuitOpenWindowMillis(-1));
        throwsException(() -> builder().circuitOpenWindowMillis(0));
    }

    @Test
    public void testCounterSlidingWindow() {
        CircuitBreakerConfig config = confOf(builder().counterSlidingWindow(twoSeconds).build());
        assertThat(config.counterSlidingWindow(), is(twoSeconds));
    }

    @Test
    public void testCounterSlidingWindowInMillis() {
        CircuitBreakerConfig config = confOf(
                builder().counterSlidingWindowMillis(twoSeconds.toMillis()).build());
        assertThat(config.counterSlidingWindow(), is(twoSeconds));
    }

    @Test
    public void testCounterSlidingWindowWithInvalidArgument() {
        throwsException(() -> builder().counterSlidingWindow(null));
        throwsException(() -> builder().counterSlidingWindow(Duration.ZERO));
        throwsException(() -> builder().counterSlidingWindow(minusDuration));
        throwsException(() -> builder().counterSlidingWindowMillis(-1));
        throwsException(() -> builder().counterSlidingWindowMillis(0));

        throwsException(() -> builder().counterSlidingWindow(oneSecond).counterUpdateInterval(twoSeconds)
                                       .build());
    }

    @Test
    public void testCounterUpdateInterval() {
        CircuitBreakerConfig config = confOf(builder().counterUpdateInterval(oneSecond).build());
        assertThat(config.counterUpdateInterval(), is(oneSecond));
    }

    @Test
    public void testCounterUpdateIntervalInMillis() {
        CircuitBreakerConfig config = confOf(
                builder().counterUpdateIntervalMillis(oneSecond.toMillis()).build());
        assertThat(config.counterUpdateInterval(), is(oneSecond));
    }

    @Test
    public void testCounterUpdateIntervalWithInvalidArgument() {
        throwsException(() -> builder().counterUpdateInterval(null));
        throwsException(() -> builder().counterUpdateInterval(Duration.ZERO));
        throwsException(() -> builder().counterUpdateInterval(minusDuration));
        throwsException(() -> builder().counterUpdateIntervalMillis(-1));
        throwsException(() -> builder().counterUpdateIntervalMillis(0));
    }

    @Test
    public void testExceptionFilter() {
        ExceptionFilter instance = e -> true;
        assertThat(confOf(builder().exceptionFilter(instance).build()).exceptionFilter(), is(instance));
    }

    @Test
    public void testExceptionFilterWithInvalidArgument() {
        throwsException(() -> builder().exceptionFilter(null));
    }
}
