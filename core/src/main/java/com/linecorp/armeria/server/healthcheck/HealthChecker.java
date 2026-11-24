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

import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import com.sun.management.OperatingSystemMXBean;

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
     * Returns a newly created {@link ListenableHealthChecker} that invokes the specified health checker
     * function repetitively on an arbitrary {@link EventExecutor} from {@link CommonPools#workerGroup()}.
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
    static ListenableHealthChecker of(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                                      Duration fallbackTtl) {
        return of(healthChecker, fallbackTtl, CommonPools.workerGroup().next());
    }

    /**
     * Returns a newly created {@link ListenableHealthChecker} that invokes the specified health checker
     * function repetitively on the specified {@link EventExecutor}.
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
    static ListenableHealthChecker of(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                                      Duration fallbackTtl, EventExecutor eventExecutor) {
        requireNonNull(fallbackTtl, "fallbackTtl");
        checkArgument(!fallbackTtl.isNegative() && !fallbackTtl.isZero(), "fallbackTtl: %s (expected: > 0)",
                      fallbackTtl);
        return new ScheduledHealthChecker(requireNonNull(healthChecker, "healthChecker"),
                                          fallbackTtl,
                                          requireNonNull(eventExecutor, "eventExecutor"));
    }

    /**
     * Creates a new instance of {@link HealthChecker} which reports health based on
     * cpu usage reported by {@link OperatingSystemMXBean}.
     * Both system and process cpu usage must be (inclusive) lower than the specified threshold in order
     * for the {@link HealthChecker} to report as healthy. If the {@link HealthChecker} is unable
     * to find a suitable {@link OperatingSystemMXBean}, an exception is thrown on construction.
     *
     * @param targetSystemCpuUsage  Target system CPU usage as a percentage (0 - 1).
     * @param targetProcessCpuUsage Target process CPU usage as a percentage (0 - 1).
     * @return an instance of {@link HealthChecker} configured with the provided CPU usage targets.
     */
    static HealthChecker ofCpu(double targetSystemCpuUsage, double targetProcessCpuUsage) {
        return new CpuHealthChecker(targetSystemCpuUsage, targetProcessCpuUsage);
    }

    /**
     * Returns a newly created {@link ListenableHealthChecker} that reports as unhealthy when the JVM has
     * deadlocked threads. Deadlocks are detected with {@link ThreadMXBean#findDeadlockedThreads()}, or with
     * {@link ThreadMXBean#findMonitorDeadlockedThreads()} if the JVM does not support monitoring of
     * ownable synchronizers. Because the detection inspects every thread in the JVM and can be expensive,
     * it runs periodically on a daemon thread dedicated to deadlock detection, which is shared by all the
     * health checkers returned by this method, and {@link #isHealthy()} returns the result of the last
     * completed check. The next check is scheduled {@code checkInterval} after the previous check
     * completes. If the JVM does not permit deadlock detection, the returned {@link ListenableHealthChecker}
     * reports as healthy and logs a warning once.
     *
     * <p>Note that the periodic check starts only when a {@link Server} starts with a
     * {@link HealthCheckService} that the returned {@link ListenableHealthChecker} was passed to directly;
     * wrapping it in another {@link HealthChecker} prevents the check from starting. It reports as
     * unhealthy until the first check completes. After the {@link Server} stops, the result of the last
     * completed check is retained. For example:
     * <pre>{@code
     * Server.builder()
     *       .service("/internal/healthcheck",
     *                HealthCheckService.of(HealthChecker.ofDeadlock(Duration.ofSeconds(10))))
     *       .build();
     * }</pre>
     *
     * @param checkInterval the interval between deadlock checks, which must be at least one millisecond
     */
    @UnstableApi
    static ListenableHealthChecker ofDeadlock(Duration checkInterval) {
        requireNonNull(checkInterval, "checkInterval");
        final long ttlMillis = checkInterval.toMillis();
        checkArgument(ttlMillis > 0, "checkInterval: %s (expected: >= 1ms)", checkInterval);
        return of(new DeadlockHealthChecker().toStatusSupplier(ttlMillis), checkInterval);
    }

    /**
     * Returns {@code true} if and only if the {@link Server} is healthy.
     */
    boolean isHealthy();
}
