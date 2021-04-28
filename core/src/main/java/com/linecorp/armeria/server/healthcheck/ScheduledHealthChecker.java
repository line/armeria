/*
 * Copyright 2021 LINE Corporation
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
 * under the Licenses
 */

package com.linecorp.armeria.server.healthcheck;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Futures;

import com.linecorp.armeria.common.util.AbstractListenable;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link ListenableHealthChecker} whose state can be set by the result of supplied CompletionStage.
 * This class can be shared by multiple {@link HealthCheckService}.
 */
final class ScheduledHealthChecker extends AbstractListenable<HealthChecker>
        implements ListenableHealthChecker {

    private final Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker;
    private final Duration fallbackTtl;
    private final EventExecutor eventExecutor;
    private final Consumer<HealthChecker> onHealthCheckerUpdate;
    private final AtomicBoolean isHealthy = new AtomicBoolean();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<ScheduledHealthCheckerImpl> impl = new AtomicReference<>();

    ScheduledHealthChecker(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                           Duration fallbackTtl, EventExecutor eventExecutor) {
        this.healthChecker = healthChecker;
        this.fallbackTtl = fallbackTtl;
        this.eventExecutor = eventExecutor;

        onHealthCheckerUpdate = latestValue -> {
            isHealthy.set(latestValue.isHealthy());
            notifyListeners(latestValue);
        };
    }

    @Override
    public boolean isHealthy() {
        return isHealthy.get();
    }

    void startHealthChecker() {
        if (requestCount.getAndIncrement() != 0) {
            return;
        }
        final ScheduledHealthCheckerImpl newlyScheduled =
                new ScheduledHealthCheckerImpl(healthChecker, fallbackTtl, eventExecutor);
        // This spin prevents the following race condition, which occurs when this instance is shared
        // by more than one server instance:
        //
        // 1. Server A starts.
        // 2. Server A stops. decrementAndGet() returns 0, but it didn't clear `impl` yet.
        // 3. Server B starts; getAndIncrement() returns 0, but `impl` isn't `null` yet.
        //
        // There should be no unexpectedly long spin here, as long as a caller makes sure to call
        // `stopHealthChecker()` for each `startHealthChecker()`, because this method is guarded by
        // `requestCount.getAndIncrement()` to allow only the first request to start to schedule
        // a task.
        for (; ; ) {
            if (impl.compareAndSet(null, newlyScheduled)) {
                newlyScheduled.addListener(onHealthCheckerUpdate);
                newlyScheduled.startHealthChecker();
                break;
            }
        }
    }

    /**
     * This method must be called after the paired startHealthChecker completes to
     * guarantee the state consistency.
     */
    void stopHealthChecker() {
        final int currentCount = requestCount.decrementAndGet();
        // Must be called after startHealthChecker, so it's always greater than or equal to 0.
        assert currentCount >= 0;
        if (currentCount != 0) {
            return;
        }

        final ScheduledHealthCheckerImpl current = impl.getAndSet(null);
        // Must be called after startHealthChecker, so it's always non null.
        assert current != null;

        current.stopHealthChecker();
        current.removeListener(onHealthCheckerUpdate);
    }

    /**
     * Used for test verification.
     */
    @VisibleForTesting
    int getRequestCount() {
        return requestCount.get();
    }

    /**
     * Used for test verification.
     */
    @VisibleForTesting
    boolean isActive() {
        return impl.get() != null;
    }

    /**
     * Health checker can be scheduled only once. Calling startHealthChecker won't work after stopHealthChecker.
     */
    private static final class ScheduledHealthCheckerImpl implements ListenableHealthChecker {
        private static final Logger logger = LoggerFactory.getLogger(ScheduledHealthCheckerImpl.class);

        private final Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker;
        private final Duration fallbackTtl;
        private final EventExecutor eventExecutor;

        private final SettableHealthChecker settableHealthChecker = new SettableHealthChecker(false);
        private State state = State.INIT;
        private volatile Future<?> scheduledFuture = Futures.immediateVoidFuture();

        ScheduledHealthCheckerImpl(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                                   Duration fallbackTtl, EventExecutor eventExecutor) {
            this.healthChecker = healthChecker;
            this.fallbackTtl = fallbackTtl;
            this.eventExecutor = eventExecutor;
        }

        @Override
        public boolean isHealthy() {
            return settableHealthChecker.isHealthy();
        }

        @Override
        public void addListener(Consumer<? super HealthChecker> listener) {
            settableHealthChecker.addListener(listener);
        }

        @Override
        public void removeListener(Consumer<?> listener) {
            settableHealthChecker.removeListener(listener);
        }

        private void startHealthChecker() {
            if (state != State.INIT) {
                return;
            }
            state = State.SCHEDULED;
            eventExecutor.execute(this::runHealthCheck);
        }

        private void stopHealthChecker() {
            if (state != State.SCHEDULED) {
                return;
            }
            state = State.FINISHED;
            scheduledFuture.cancel(true);
        }

        private void runHealthCheck() {
            if (state != State.SCHEDULED) {
                return;
            }
            try {
                healthChecker.get().handle((result, throwable) -> {
                    final boolean isHealthy;
                    final long intervalMillis;
                    if (throwable != null) {
                        logger.warn("Health checker throws an exception, schedule the next check after {}ms.",
                                    fallbackTtl.toMillis(), throwable);
                        isHealthy = false;
                        intervalMillis = fallbackTtl.toMillis();
                    } else if (result == null) {
                        logger.warn("Health checker returns an unexpected null result, "
                                    + "schedule the next check after {}ms.",
                                    fallbackTtl.toMillis(), throwable);
                        isHealthy = false;
                        intervalMillis = fallbackTtl.toMillis();
                    } else {
                        isHealthy = result.isHealthy();
                        intervalMillis = result.ttlMillis();
                    }
                    settableHealthChecker.setHealthy(isHealthy);
                    scheduledFuture = eventExecutor.schedule(this::runHealthCheck, intervalMillis,
                                                             TimeUnit.MILLISECONDS);
                    return null;
                });
            } catch (Throwable throwable) {
                logger.warn("Health checker throws an exception, schedule the next check after {}ms.",
                            fallbackTtl.toMillis(), throwable);
                settableHealthChecker.setHealthy(false);
                scheduledFuture = eventExecutor.schedule(this::runHealthCheck, fallbackTtl.toMillis(),
                                                         TimeUnit.MILLISECONDS);
            }
        }

        enum State {
            INIT,
            SCHEDULED,
            FINISHED
        }
    }
}
