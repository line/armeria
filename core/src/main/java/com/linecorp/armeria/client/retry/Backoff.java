/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.retry.DefaultBackoffHolder.defaultBackoff;
import static com.linecorp.armeria.client.retry.FixedBackoff.NO_DELAY;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.Unwrappable;

/**
 * Controls back off between attempts in a single retry operation.
 */
@FunctionalInterface
public interface Backoff extends Unwrappable {

    /**
     * Returns the default {@link Backoff}.
     *
     * @see Flags#defaultBackoffSpec()
     */
    static Backoff ofDefault() {
        return defaultBackoff;
    }

    /**
     * Returns a {@link Backoff} that will never wait between attempts. In most cases, using back off
     * without delay is very dangerous. Please consider using {@link #exponential(long, long)} with
     * {@link #withJitter(double)} or {@link #fixed(long)} with pre calculated delay depending on the situation.
     */
    static Backoff withoutDelay() {
        return NO_DELAY;
    }

    /**
     * Returns a {@link Backoff} that waits a fixed delay between attempts.
     */
    static Backoff fixed(long delayMillis) {
        return new FixedBackoff(delayMillis);
    }

    /**
     * Returns a {@link Backoff} that waits an exponentially-increasing amount of time between attempts.
     */
    static Backoff exponential(long initialDelayMillis, long maxDelayMillis) {
        return exponential(initialDelayMillis, maxDelayMillis, 2.0);
    }

    /**
     * Returns a {@link Backoff} that waits an exponentially-increasing amount of time between attempts.
     */
    static Backoff exponential(long initialDelayMillis, long maxDelayMillis, double multiplier) {
        return new ExponentialBackoff(initialDelayMillis, maxDelayMillis, multiplier);
    }

    /**
     * Returns a {@link Backoff} for which the backoff delay increases in line with the Fibonacci sequence
     * f(n) = f(n-1) + f(n-2) where f(0) = f(1) = {@code initialDelayMillis}.
     */
    static Backoff fibonacci(long initialDelayMillis, long maxDelayMillis) {
        return new FibonacciBackoff(initialDelayMillis, maxDelayMillis);
    }

    /**
     * Returns a {@link Backoff} that computes backoff delay which is a random value between
     * {@code minDelayMillis} and {@code maxDelayMillis} chosen by {@link ThreadLocalRandom}.
     */
    static Backoff random(long minDelayMillis, long maxDelayMillis) {
        return random(minDelayMillis, maxDelayMillis, ThreadLocalRandom::current);
    }

    /**
     * Returns a {@link Backoff} that computes backoff delay which is a random value between
     * {@code minDelayMillis} and {@code maxDelayMillis}.
     */
    static Backoff random(long minDelayMillis, long maxDelayMillis, Supplier<Random> randomSupplier) {
        return new RandomBackoff(minDelayMillis, maxDelayMillis, randomSupplier);
    }

    /**
     * Creates a new {@link Backoff} that computes backoff delay using one of
     * {@link #exponential(long, long, double)}, {@link #fibonacci(long, long)}, {@link #fixed(long)}
     * and {@link #random(long, long)} chaining with {@link #withJitter(double, double)} and
     * {@link #withMaxAttempts(int)} from the {@code specification} string that conforms to
     * the following format:
     * <ul>
     *   <li>{@code exponential=[initialDelayMillis:maxDelayMillis:multiplier]} is for
     *       {@link Backoff#exponential(long, long, double)} (multiplier will be 2.0 if it's omitted)</li>
     *   <li>{@code fibonacci=[initialDelayMillis:maxDelayMillis]} is for
     *       {@link Backoff#fibonacci(long, long)}</li>
     *   <li>{@code fixed=[delayMillis]} is for {@link Backoff#fixed(long)}</li>
     *   <li>{@code random=[minDelayMillis:maxDelayMillis]} is for {@link Backoff#random(long, long)}</li>
     *   <li>{@code jitter=[minJitterRate:maxJitterRate]} is for {@link Backoff#withJitter(double, double)}
     *       (if only one jitter value is specified, it will be used for {@link Backoff#withJitter(double)}</li>
     *   <li>{@code maxAttempts=[maxAttempts]} is for {@link Backoff#withMaxAttempts(int)}</li>
     * </ul>
     * The order of options does not matter, and the {@code specification} needs at least one option.
     * If you don't specify the base option exponential backoff will be used. If you only specify
     * a base option, jitter and maxAttempts will be set by default values. For example:
     * <ul>
     *   <li>{@code exponential=200:10000:2.0,jitter=0.2} (default)</li>
     *   <li>{@code exponential=200:10000,jitter=0.2,maxAttempts=50} (multiplier omitted)</li>
     *   <li>{@code fibonacci=200:10000,jitter=0.2,maxAttempts=50}</li>
     *   <li>{@code fixed=100,jitter=-0.5:0.2,maxAttempts=10} (fixed backoff with jitter variation)</li>
     *   <li>{@code random=200:1000} (jitter and maxAttempts will be set by default values)</li>
     * </ul>
     *
     * @param specification the specification used to create a {@link Backoff}
     */
    static Backoff of(String specification) {
        return BackoffSpec.parse(specification).build();
    }

    /**
     * Returns the number of milliseconds to wait for before attempting a retry.
     *
     * @param numAttemptsSoFar the number of attempts made by a client so far, including the first attempt and
     *                         its following retries.
     *
     * @return the number of milliseconds to wait for before attempting a retry,
     *         or a negative value if no further retry has to be made.
     *
     * @throws IllegalArgumentException if {@code numAttemptsSoFar} is equal to or less than {@code 0}
     */
    long nextDelayMillis(int numAttemptsSoFar);

    /**
     * Undecorates this {@link Backoff} to find the {@link Backoff} which is an instance of the specified
     * {@code type}.
     *
     * @param type the type of the desired {@link Backoff}
     * @return the {@link Backoff} which is an instance of {@code type} if this {@link Backoff}
     *         decorated such a {@link Backoff}, or {@code null} otherwise.
     *
     * @see Unwrappable
     */
    @Override
    default <T> T as(Class<T> type) {
        return Unwrappable.super.as(type);
    }

    /**
     * Undecorates this {@link Backoff} and returns the object being decorated.
     * If this {@link Backoff} is the innermost object, this method returns itself.
     *
     * @see Unwrappable
     */
    @Override
    default Backoff unwrap() {
        return (Backoff) Unwrappable.super.unwrap();
    }

    /**
     * Returns a {@link Backoff} that adds a random jitter value to the original delay using
     * <a href="https://www.awsarchitectureblog.com/2015/03/backoff.html">full jitter</a> strategy.
     * The {@code jitterRate} is used to calculate the lower and upper bound of the ultimate delay.
     * The lower bound will be {@code ((1 - jitterRate) * originalDelay)} and the upper bound will be
     * {@code ((1 + jitterRate) * originalDelay)}. For example, if the delay returned by
     * {@link Backoff#exponential(long, long)} is 1000 milliseconds and the provided jitter value is 0.3,
     * the ultimate backoff delay will be chosen between 1000 * (1 - 0.3) and 1000 * (1 + 0.3)
     * by {@link ThreadLocalRandom}. The rate value should be between 0.0 and 1.0.
     *
     * @param jitterRate the rate that used to calculate the lower and upper bound of the backoff delay
     * @throws IllegalArgumentException if {@code jitterRate} is a negative value or greater than 1.0
     */
    default Backoff withJitter(double jitterRate) {
        checkArgument(0.0 <= jitterRate && jitterRate <= 1.0,
                      "jitterRate: %s (expected: >= 0.0 and <= 1.0)", jitterRate);
        return withJitter(-jitterRate, jitterRate, ThreadLocalRandom::current);
    }

    /**
     * Returns a {@link Backoff} that adds a random jitter value to the original delay using
     * <a href="https://www.awsarchitectureblog.com/2015/03/backoff.html">full jitter</a> strategy.
     * The {@code minJitterRate} and {@code maxJitterRate} is used to calculate the lower and upper bound
     * of the ultimate delay. The lower bound will be {@code ((1 - minJitterRate) * originalDelay)} and the
     * upper bound will be {@code ((1 + maxJitterRate) * originalDelay)}. For example, if the delay
     * returned by {@link Backoff#exponential(long, long)} is 1000 milliseconds and the {@code minJitterRate}
     * is -0.2, {@code maxJitterRate} is 0.3, the ultimate backoff delay will be chosen between
     * 1000 * (1 - 0.2) and 1000 * (1 + 0.3) by {@link ThreadLocalRandom}.
     * The rate values should be between -1.0 and 1.0.
     *
     * @param minJitterRate the rate that used to calculate the lower bound of the backoff delay
     * @param maxJitterRate the rate that used to calculate the upper bound of the backoff delay
     * @throws IllegalArgumentException if {@code minJitterRate} is greater than {@code maxJitterRate} or if the
     *                                  {@code minJitterRate} and {@code maxJitterRate} values are not in
     *                                  between -1.0 and 1.0
     */
    default Backoff withJitter(double minJitterRate, double maxJitterRate) {
        return withJitter(minJitterRate, maxJitterRate, ThreadLocalRandom::current);
    }

    /**
     * Returns a {@link Backoff} that adds a random jitter value to the original delay using
     * <a href="https://www.awsarchitectureblog.com/2015/03/backoff.html">full jitter</a> strategy.
     * The {@code minJitterRate} and {@code maxJitterRate} is used to calculate the lower and upper bound
     * of the ultimate delay. The lower bound will be {@code ((1 - minJitterRate) * originalDelay)} and the
     * upper bound will be {@code ((1 + maxJitterRate) * originalDelay)}. For example, if the delay
     * returned by {@link Backoff#exponential(long, long)} is 1000 milliseconds and the {@code minJitterRate}
     * is -0.2, {@code maxJitterRate} is 0.3, the ultimate backoff delay will be chosen between
     * 1000 * (1 - 0.2) and 1000 * (1 + 0.3). The rate values should be between -1.0 and 1.0.
     *
     * @param minJitterRate  the rate that used to calculate the lower bound of the backoff delay
     * @param maxJitterRate  the rate that used to calculate the upper bound of the backoff delay
     * @param randomSupplier the supplier that provides {@link Random} in order to calculate the ultimate delay
     * @throws IllegalArgumentException if {@code minJitterRate} is greater than {@code maxJitterRate} or if the
     *                                  {@code minJitterRate} and {@code maxJitterRate} values are not in
     *                                  between -1.0 and 1.0
     */
    default Backoff withJitter(double minJitterRate, double maxJitterRate, Supplier<Random> randomSupplier) {
        return new JitterAddingBackoff(this, minJitterRate, maxJitterRate, randomSupplier);
    }

    /**
     * Returns a {@link Backoff} which limits the number of attempts up to the specified value.
     */
    default Backoff withMaxAttempts(int maxAttempts) {
        return new AttemptLimitingBackoff(this, maxAttempts);
    }
}
