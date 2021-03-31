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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

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
    private final AtomicBoolean isHealthy;
    private final AtomicInteger requestCount;
    private final AtomicReference<ScheduledHealthCheckerImpl> impl;

    ScheduledHealthChecker(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                           Duration facllbackTtl, EventExecutor eventExecutor) {
        this.healthChecker = healthChecker;
        this.fallbackTtl = facllbackTtl;
        this.eventExecutor = eventExecutor;

        isHealthy = new AtomicBoolean(false);
        requestCount = new AtomicInteger(0);
        impl = new AtomicReference<>();
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
        if (requestCount.incrementAndGet() != 1) {
            return;
        }
        final ScheduledHealthCheckerImpl newlyScheduled =
                new ScheduledHealthCheckerImpl(healthChecker, fallbackTtl, eventExecutor);
        for (;;) {
            if (impl.compareAndSet(null, newlyScheduled)) {
                newlyScheduled.addListener(onHealthCheckerUpdate);
                newlyScheduled.startHealthChecker();
                break;
            }
        }
    }

    void stopHealthChecker() {
        final int previousCount = requestCount.getAndUpdate(count -> {
            if (count > 0) {
                return count - 1;
            }
            return 0;
        });
        if (previousCount != 1) {
            return;
        }

        for (;;) {
            final ScheduledHealthCheckerImpl current = impl.get();
            if(current == null){
                continue;
            }
            if (impl.compareAndSet( current, null)) {
                current.stopHealthChecker();
                current.removeListener(onHealthCheckerUpdate);
                break;
            }
        }
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
    private static class ScheduledHealthCheckerImpl implements ListenableHealthChecker {

        private final Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker;
        private final Duration fallbackTtl;
        private final SettableHealthChecker settableHealthChecker;
        private final EventExecutor eventExecutor;

        private State state;
        private volatile Future<?> scheduledFuture;

        ScheduledHealthCheckerImpl(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                                   Duration fallbackTtl, EventExecutor eventExecutor) {
            this.healthChecker = healthChecker;
            this.fallbackTtl = fallbackTtl;
            this.eventExecutor = eventExecutor;
            settableHealthChecker = new SettableHealthChecker(false);

            state = State.INIT;
            scheduledFuture = Futures.immediateVoidFuture();
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
                    final long intervalMills;
                    if (throwable != null) {
                        isHealthy = false;
                        intervalMills = fallbackTtl.toMillis();
                    } else {
                        isHealthy = result.isHealthy();
                        intervalMills = result.ttlMillis();
                    }
                    settableHealthChecker.setHealthy(isHealthy);
                    scheduledFuture = eventExecutor.schedule(this::runHealthCheck, intervalMills,
                                                             TimeUnit.MILLISECONDS);
                    return null;
                });
            } catch (Throwable throwable) {
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
