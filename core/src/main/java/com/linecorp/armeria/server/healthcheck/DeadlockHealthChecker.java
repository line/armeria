/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static java.util.Objects.requireNonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.ThreadFactories;

/**
 * A {@link HealthChecker} that reports as unhealthy when the JVM has deadlocked threads.
 *
 * <p>Deadlocks are detected with {@link ThreadMXBean#findDeadlockedThreads()}, which inspects every thread
 * in the JVM and therefore can be expensive. Use {@link HealthChecker#ofDeadlock(Duration)} to run the
 * check periodically instead of calling {@link #isHealthy()} on every health check request. For example:
 * <pre>{@code
 * Server.builder()
 *       .service("/internal/healthcheck",
 *                HealthCheckService.of(HealthChecker.ofDeadlock(Duration.ofSeconds(10))))
 *       .build();
 * }</pre>
 */
final class DeadlockHealthChecker implements HealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(DeadlockHealthChecker.class);

    // A deadlock in user code can exhaust CommonPools.blockingTaskExecutor(), which would prevent the
    // periodic check from ever running, so the check runs on its own daemon thread instead.
    private static final Executor deadlockCheckExecutor = Executors.newSingleThreadExecutor(
            ThreadFactories.newThreadFactory("armeria-deadlock-checker-executor", true));

    private final ThreadMXBean threadMXBean;
    private final boolean synchronizerUsageSupported;
    private final AtomicBoolean deadlockReported = new AtomicBoolean();
    private final AtomicBoolean detectionFailureReported = new AtomicBoolean();

    DeadlockHealthChecker() {
        this(ManagementFactory.getThreadMXBean());
    }

    @VisibleForTesting
    DeadlockHealthChecker(ThreadMXBean threadMXBean) {
        this.threadMXBean = requireNonNull(threadMXBean, "threadMXBean");
        synchronizerUsageSupported = threadMXBean.isSynchronizerUsageSupported();
        if (!synchronizerUsageSupported) {
            logger.warn("The JVM does not support monitoring of ownable synchronizers; " +
                        "deadlocks that involve java.util.concurrent locks will not be detected.");
        }
    }

    /**
     * Returns a {@link Supplier} that performs the deadlock check on a daemon thread shared by all
     * {@link DeadlockHealthChecker}s rather than on {@link CommonPools#blockingTaskExecutor()},
     * which a deadlock in user code can exhaust.
     *
     * @param ttlMillis the delay before the next check, which is passed to {@link HealthCheckStatus}
     */
    Supplier<CompletionStage<HealthCheckStatus>> toStatusSupplier(long ttlMillis) {
        return () -> CompletableFuture.supplyAsync(() -> new HealthCheckStatus(isHealthy(), ttlMillis),
                                                   deadlockCheckExecutor);
    }

    @Override
    public boolean isHealthy() {
        final long[] deadlockedThreadIds;
        try {
            deadlockedThreadIds = findDeadlockedThreads();
        } catch (SecurityException e) {
            // Report as healthy because it is impossible to tell whether a deadlock exists,
            // and log only once so that a short check interval does not flood the log.
            if (detectionFailureReported.compareAndSet(false, true)) {
                logger.warn("Failed to detect deadlocks; reporting as healthy:", e);
            }
            return true;
        }

        if (deadlockedThreadIds == null || deadlockedThreadIds.length == 0) {
            if (deadlockReported.compareAndSet(true, false)) {
                logger.info("The deadlock has been resolved.");
            }
            return true;
        }

        // Log only once per deadlock so that a short check interval does not flood the log.
        if (deadlockReported.compareAndSet(false, true)) {
            logger.warn("Detected {} deadlocked thread(s): {}",
                        deadlockedThreadIds.length, describeThreads(deadlockedThreadIds));
        }
        return false;
    }

    private long @Nullable [] findDeadlockedThreads() {
        if (synchronizerUsageSupported) {
            // Finds deadlocks that involve object monitors and ownable synchronizers such as ReentrantLock.
            return threadMXBean.findDeadlockedThreads();
        }
        // findDeadlockedThreads() throws an UnsupportedOperationException on a JVM that does not
        // support monitoring of ownable synchronizers, so fall back to the monitor-only detection.
        return threadMXBean.findMonitorDeadlockedThreads();
    }

    private String describeThreads(long[] threadIds) {
        // Returns an array of the same length as threadIds; an element is null if the thread is not alive.
        final ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds);
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < threadIds.length; i++) {
            if (i != 0) {
                buf.append(", ");
            }
            final ThreadInfo threadInfo = threadInfos[i];
            if (threadInfo == null) {
                buf.append("unknown(").append(threadIds[i]).append(')');
                continue;
            }
            buf.append(threadInfo.getThreadName()).append('(').append(threadIds[i]).append(')');
            final String lockName = threadInfo.getLockName();
            if (lockName != null) {
                buf.append(" waiting for ").append(lockName);
                final String lockOwnerName = threadInfo.getLockOwnerName();
                if (lockOwnerName != null) {
                    buf.append(" owned by ").append(lockOwnerName)
                       .append('(').append(threadInfo.getLockOwnerId()).append(')');
                }
            }
        }
        return buf.toString();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("synchronizerUsageSupported", synchronizerUsageSupported)
                          .add("deadlockReported", deadlockReported.get())
                          .toString();
    }
}
