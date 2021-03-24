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
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class ScheduledHealthChecker extends AbstractListenable<HealthChecker>
        implements ListenableHealthChecker, SafeCloseable {
    private final AtomicBoolean isHealthy = new AtomicBoolean(false);
    private final Supplier<? extends CompletionStage<Boolean>> healthChecker;
    private final EventLoop eventLoop;
    private final Backoff backoff;
    private final boolean scheduleAfterCheckerComplete;
    @VisibleForTesting
    final Set<Future<?>> inScheduledFutures = ConcurrentHashMap.newKeySet();

    ScheduledHealthChecker(Supplier<? extends CompletionStage<Boolean>> healthChecker, Duration period,
                           double jitter, boolean scheduleAfterCheckerComplete, EventLoop eventLoop) {
        this.healthChecker = healthChecker;
        this.scheduleAfterCheckerComplete = scheduleAfterCheckerComplete;
        this.eventLoop = eventLoop;
        backoff = Backoff.fixed(period.toMillis()).withJitter(jitter);

        eventLoop.execute(createTask());
    }

    @Override
    public boolean isHealthy() {
        return isHealthy.get();
    }

    @Override
    public void close() {
        for (Future<?> future : inScheduledFutures) {
            future.cancel(true);
        }
    }

    private Runnable createTask() {
        return () -> {
            if (!scheduleAfterCheckerComplete) {
                scheduleHealthChecker();
            }
            healthChecker.get().whenComplete((result, throwable) -> {
                final boolean isHealthy;
                if (throwable != null) {
                    isHealthy = false;
                } else {
                    isHealthy = result;
                }
                final boolean oldValue = this.isHealthy.getAndSet(isHealthy);
                if (oldValue != isHealthy) {
                    notifyListeners(this);
                }
                if (scheduleAfterCheckerComplete) {
                    scheduleHealthChecker();
                }
            });
        };
    }

    private void scheduleHealthChecker() {
        final ScheduledFuture<?> future = eventLoop.schedule(createTask(), backoff.nextDelayMillis(1),
                                                             TimeUnit.MILLISECONDS);
        inScheduledFutures.add(future);
        future.addListener(inScheduledFutures::remove);
    }
}
