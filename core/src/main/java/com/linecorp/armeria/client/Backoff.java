/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.Backoff.FixedBackoff.NO_DELAY;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

import com.google.common.collect.ImmutableList;

/**
 * Control back off between attemtps in a single retry operation.
 */
@FunctionalInterface
public interface Backoff {
    /**
     * Returns a new back off value in milliseconds.
     */
    long nextIntervalMillis();

    /**
     * Returns a {@link Backoff} that provides zero interval.
     */
    static Backoff withoutDelay() {
        return NO_DELAY;
    }

    /**
     * Returns a {@link Backoff} that provides a fixed interval between two attempts.
     */
    static Backoff fixed(long intervalMillis) {
        return new FixedBackoff(intervalMillis);
    }

    /**
     * Returns a {@link Backoff} that provides an interval (that increases exponentially) between two attempts.
     */
    static Backoff exponential(long minIntervalMillis, long maxIntervalMillis) {
        return new ExponentialBackoff(minIntervalMillis, maxIntervalMillis);
    }

    /**
     * Returns a {@link Backoff} that provides a random interval between two attempts.
     */
    static Backoff random(long minJitterMills, long maxJitterMills) {
        return new RandomBackoff(minJitterMills, maxJitterMills);
    }

    /**
     * Returns a {@link Backoff} that provides an interval that increases using
     * <a href="https://www.awsarchitectureblog.com/2015/03/backoff.html">full jitter</a> strategy.
     */
    static Backoff exponentialWithJitter(long minIntervalMills, long maxIntervalMills,
                                         long minJitterMills, long maxJitterMills) {
        return new CompositeBackoff(exponential(minIntervalMills, maxIntervalMills),
                                    random(minJitterMills, maxJitterMills));
    }

    final class FixedBackoff implements Backoff {
        static final Backoff NO_DELAY = fixed(0);

        private final long intervalMills;

        FixedBackoff(long intervalMills) {
            this.intervalMills = intervalMills;
        }

        @Override
        public long nextIntervalMillis() {
            return intervalMills;
        }
    }

    final class ExponentialBackoff implements Backoff {
        private long currentIntervalMills;
        private final long maxIntervalMills;

        ExponentialBackoff(long minIntervalMills, long maxIntervalMills) {
            currentIntervalMills = minIntervalMills;
            this.maxIntervalMills = maxIntervalMills;
        }

        @Override
        public long nextIntervalMillis() {
            long nextInterval = currentIntervalMills;
            currentIntervalMills = Math.min(currentIntervalMills * 2, maxIntervalMills);
            return nextInterval;
        }
    }

    final class RandomBackoff implements Backoff {
        private final LongSupplier nextInterval;

        RandomBackoff(long minIntervalMills, long maxIntervalMills) {
            nextInterval = () -> ThreadLocalRandom.current().nextLong(minIntervalMills, maxIntervalMills);
        }

        @Override
        public long nextIntervalMillis() {
            return nextInterval.getAsLong();
        }
    }

    final class CompositeBackoff implements Backoff {
        private final List<Backoff> backoffs;

        CompositeBackoff(Collection<? extends Backoff> backoffs) {
            this.backoffs = ImmutableList.copyOf(backoffs);
        }

        private CompositeBackoff(Backoff... backoffs) {
            this.backoffs = ImmutableList.copyOf(backoffs);
        }

        @Override
        public long nextIntervalMillis() {
            return backoffs.stream().mapToLong(Backoff::nextIntervalMillis).sum();
        }
    }
}
