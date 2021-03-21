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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.util.AbstractListenable;

import io.netty.channel.EventLoop;

/**
 * Trigger supplied health checker after constructing and subsequently with the given interval.
 */
final class FixedRateHealthChecker extends AbstractListenable<HealthChecker>
        implements ListenableHealthChecker {
    private final AtomicBoolean isHealthy = new AtomicBoolean(false);
    private final Supplier<CompletionStage<Boolean>> healthChecker;

    FixedRateHealthChecker(Supplier<CompletionStage<Boolean>> healthChecker, Duration interval,
                           double jitter, EventLoop eventLoop) {
        this.healthChecker = healthChecker;

        eventLoop.scheduleAtFixedRate(createTask(), 0,
                                      Backoff.fixed(interval.toMillis())
                                             .withJitter(jitter)
                                             .nextDelayMillis(1),
                                      TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isHealthy() {
        return isHealthy.get();
    }

    private Runnable createTask() {
        return () -> {
            final CompletionStage<Boolean> future = healthChecker.get();
            future.whenComplete((result, throwable) -> {
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
            });
        };
    }
}
