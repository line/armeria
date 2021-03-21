/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.healthcheck;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.server.Server;

import io.netty.channel.EventLoop;

/**
 * Determines whether the {@link Server} is healthy. All registered {@link HealthChecker}s must return
 * {@code true} for the {@link Server} to be considered healthy.
 */
@FunctionalInterface
public interface HealthChecker {

    /**
     * Create a {@link HealthChecker} which executing supplied health checker {@link Supplier} on a random
     * {@link EventLoop} from {@link CommonPools#workerGroup()} after constructing and subsequently with the
     * given interval.
     *
     * @param healthChecker the {@link Supplier} of {@link CompletionStage} that provides the result of health
     * @param interval the interval between successive executions
     * @param jitter the rate that used to calculate the lower and upper bound of the backoff delay
     */
    static HealthChecker ofFixedRate(Supplier<CompletionStage<Boolean>> healthChecker, Duration interval,
                                     double jitter) {
        return new FixedRateHealthChecker(healthChecker, interval, jitter, CommonPools.workerGroup().next());
    }

    /**
     * Create a {@link HealthChecker} which executing supplied health checker {@link Supplier} on a random
     * {@link EventLoop} from {@link CommonPools#workerGroup()} after constructing and subsequently with the
     * given delay between the completion of supplied {@link CompletionStage} and the commencement of the next.
     *
     * @param healthChecker the {@link Supplier} of {@link CompletionStage} that provides the result of health
     * @param delay  fixed delay between attempts
     * @param jitter the rate that used to calculate the lower and upper bound of the backoff delay
     */
    static HealthChecker ofFixedDelay(Supplier<CompletionStage<Boolean>> healthChecker, Duration delay,
                                      double jitter) {
        return new FixedDelayHealthChecker(healthChecker, delay, jitter, CommonPools.workerGroup().next());
    }

    /**
     * Returns {@code true} if and only if the {@link Server} is healthy.
     */
    boolean isHealthy();
}
