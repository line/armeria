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

import static com.google.common.base.Preconditions.checkArgument;

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
     * @param period the period between successive executions
     * @param jitterRate the rate that used to calculate the lower and upper bound of the backoff delay
     */
    static HealthChecker ofFixedRate(Supplier<? extends CompletionStage<Boolean>> healthChecker,
                                     Duration period, double jitterRate) {
        checkArgument(0.0 <= jitterRate && jitterRate <= 1.0,
                      "jitterRate: %s (expected: >= 0.0 and <= 1.0)", jitterRate);
        return new ScheduledHealthChecker(healthChecker, period, jitterRate, false,
                                          CommonPools.workerGroup().next());
    }

    /**
     * Create a {@link HealthChecker} which executing supplied health checker {@link Supplier} on a random
     * {@link EventLoop} from {@link CommonPools#workerGroup()} after constructing and subsequently with the
     * given delay between the completion of supplied {@link CompletionStage} and the commencement of the next.
     *
     * @param healthChecker the {@link Supplier} of {@link CompletionStage} that provides the result of health
     * @param delay  fixed delay between attempts
     * @param jitterRate the rate that used to calculate the lower and upper bound of the backoff delay
     */
    static HealthChecker ofFixedDelay(Supplier<? extends CompletionStage<Boolean>> healthChecker,
                                      Duration delay, double jitterRate) {
        checkArgument(0.0 <= jitterRate && jitterRate <= 1.0,
                      "jitterRate: %s (expected: >= 0.0 and <= 1.0)", jitterRate);
        return new ScheduledHealthChecker(healthChecker, delay, jitterRate, true,
                                          CommonPools.workerGroup().next());
    }

    /**
     * Returns {@code true} if and only if the {@link Server} is healthy.
     */
    boolean isHealthy();
}
