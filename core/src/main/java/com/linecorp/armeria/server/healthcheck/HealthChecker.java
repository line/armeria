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
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Server;

import io.netty.util.concurrent.EventExecutor;

/**
 * Determines whether the {@link Server} is healthy. All registered {@link HealthChecker}s must return
 * {@code true} for the {@link Server} to be considered healthy.
 */
@FunctionalInterface
public interface HealthChecker {

    /**
     * Returns a newly created {@link HealthChecker} that invokes the specified health checker
     * repetitively on an arbitrary {@link EventExecutor} from {@link CommonPools#workerGroup()}.
     *
     * @param healthChecker A {@link Supplier} that performs a health check asynchronously and
     *                      returns a future that will complete with a {@link HealthCheckStatus}.
     *                      {@link HealthCheckStatus#ttlMillis()} determines the delay before the
     *                      next health check. If the future completed exceptionally, the specified
     *                      {@code fallbackTtl} will be used instead to determine the delay.
     *                      {@link Supplier} should avoid returning null or throwing exception.
     *                      The {@link CompletionStage} from {@link Supplier} should avoid completing
     *                      with null result or failing.
     * @param fallbackTtl   The amount of delay between each health check if the previous health
     *                      check failed unexpectedly so it's not possible to determine how long
     *                      we have to wait until the next health check.
     *
     * @see #of(Supplier, Duration, EventExecutor)
     */
    @UnstableApi
    static HealthChecker of(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                            Duration fallbackTtl) {
        return of(healthChecker, fallbackTtl, CommonPools.workerGroup().next());
    }

    /**
     * Returns a newly created {@link HealthChecker} that invokes the specified health checker
     * repetitively on the specified {@link EventExecutor}.
     *
     * @param healthChecker A {@link Supplier} that performs a health check asynchronously and
     *                      returns a future that will complete with a {@link HealthCheckStatus}.
     *                      {@link HealthCheckStatus#ttlMillis()} determines the delay before the
     *                      next health check. If the future completed exceptionally, the specified
     *                      {@code fallbackTtl} will be used instead to determine the delay.
     *                      {@link Supplier} should avoid returning null or throwing exception.
     *                      The {@link CompletionStage} from {@link Supplier} should avoid completing
     *                      with null result or failing.
     * @param fallbackTtl   The amount of delay between each health check if the previous health
     *                      check failed unexpectedly so it's not possible to determine how long
     *                      we have to wait until the next health check.
     * @param eventExecutor The {@link EventExecutor} that will invoke the specified {@code healthChecker}.
     */
    @UnstableApi
    static HealthChecker of(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                            Duration fallbackTtl, EventExecutor eventExecutor) {
        requireNonNull(fallbackTtl, "fallbackTtl");
        checkArgument(!fallbackTtl.isNegative() && !fallbackTtl.isZero(), "fallbackTtl: %s (expected: > 0)",
                      fallbackTtl);
        return new ScheduledHealthChecker(requireNonNull(healthChecker, "healthChecker"),
                                          fallbackTtl,
                                          requireNonNull(eventExecutor, "eventExecutor"));
    }

    /**
     * Returns {@code true} if and only if the {@link Server} is healthy.
     */
    boolean isHealthy();
}
