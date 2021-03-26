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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;

/**
 * A simple {@link ListenableHealthChecker} whose state can be set by the result of supplied CompletionStage.
 */
final class ScheduledHealthChecker implements ListenableHealthChecker {
    private final Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker;
    private final Duration maxTtl;
    private final SettableHealthChecker settableHealthChecker;
    private final EventExecutor eventExecutor;

    ScheduledHealthChecker(Supplier<? extends CompletionStage<HealthCheckStatus>> healthChecker,
                           Duration maxTtl, EventExecutor eventExecutor) {
        this.healthChecker = healthChecker;
        this.maxTtl = maxTtl;
        settableHealthChecker = new SettableHealthChecker(false);
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

    void startHealthChecker(Consumer<Future<?>> scheduledListener) {
        eventExecutor.execute(createTask(scheduledListener));
    }

    private Runnable createTask(Consumer<Future<?>> scheduledListener) {
        return () -> {
            try {
                healthChecker.get().handle((result, throwable) -> {
                    final boolean isHealthy;
                    final long intervalMills;
                    if (throwable != null) {
                        isHealthy = false;
                        intervalMills = maxTtl.toMillis();
                    } else {
                        isHealthy = result.isHealthy();
                        intervalMills = result.ttlMillis();
                    }
                    settableHealthChecker.setHealthy(isHealthy);
                    final Future<?> scheduledFuture = eventExecutor.schedule(createTask(scheduledListener),
                                                                             intervalMills,
                                                                             TimeUnit.MILLISECONDS);
                    scheduledListener.accept(scheduledFuture);
                    return null;
                });
            } catch (Throwable throwable) {
                settableHealthChecker.setHealthy(false);
                final Future<?> scheduledFuture = eventExecutor.schedule(createTask(scheduledListener),
                                                                         maxTtl.toMillis(),
                                                                         TimeUnit.MILLISECONDS);
                scheduledListener.accept(scheduledFuture);
            }
        };
    }
}
