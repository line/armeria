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

import io.netty.util.concurrent.EventExecutor;

/**
 * Determines whether the {@link Server} is healthy. All registered {@link HealthChecker}s must return
 * {@code true} for the {@link Server} to be considered healthy.
 */
@FunctionalInterface
public interface HealthChecker {

    /**
     * Create a {@link HealthChecker} which executing supplied health checker {@link Supplier} on a random
     * {@link EventExecutor} from {@link CommonPools#workerGroup()} after constructing and subsequently with the
     * given delay between the completion of supplied {@link CompletionStage} and the commencement of the next.
     *
     * @see #of(Supplier, Duration, EventExecutor)
     */
    static HealthChecker of(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                            Duration maxTtl) {
        return new ScheduledHealthChecker(healthChecker, maxTtl, CommonPools.workerGroup().next());
    }

    /**
     * Create a {@link HealthChecker} which executing supplied health checker {@link Supplier} on the
     * {@link EventExecutor} after constructing and subsequently with the given delay between the completion of
     * supplied {@link CompletionStage} and the commencement of the next.
     *
     * @param healthChecker the {@link Supplier} of {@link CompletionStage} that provides the result of health
     *                      and interval for next checking
     * @param maxTtl used when healthChecker throws exception or returned a failed {@link CompletionStage}
     * @param eventExecutor the executor executing supplied health checker
     */
    static HealthChecker of(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                            Duration maxTtl, EventExecutor eventExecutor) {
        return new ScheduledHealthChecker(healthChecker, maxTtl, eventExecutor);
    }

    /**
     * Returns {@code true} if and only if the {@link Server} is healthy.
     */
    boolean isHealthy();
}
