/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TextFormatter;

import akka.japi.Pair;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class DefaultUnhandledExceptionsReporter implements UnhandledExceptionsReporter {

    private static final Logger logger = LoggerFactory.getLogger(DefaultUnhandledExceptionsReporter.class);
    private static final AtomicIntegerFieldUpdater<DefaultUnhandledExceptionsReporter> scheduledUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultUnhandledExceptionsReporter.class,
                                                 "scheduled");

    private final long intervalMillis;
    // Note: We keep both Micrometer Counter and our own counter because Micrometer Counter
    //       doesn't count anything if the MeterRegistry is a CompositeMeterRegistry
    //       without an actual MeterRegistry implementation.
    private final Counter micrometerCounter;
    private final LongAdder counter;
    private long lastExceptionsCount;
    private volatile int scheduled;

    @Nullable
    private ScheduledFuture<?> reportingTaskFuture;
    @Nullable
    private Map<Throwable, Pair<LongAdder, ServiceRequestContext>> thrownExceptions;

    DefaultUnhandledExceptionsReporter(MeterRegistry meterRegistry, long intervalMillis) {
        this.intervalMillis = intervalMillis;
        micrometerCounter = meterRegistry.counter("armeria.server.exceptions.unhandled");
        counter = new LongAdder();
    }

    @Override
    public void report(ServiceRequestContext ctx, Throwable cause) {
        if (reportingTaskFuture == null && scheduledUpdater.compareAndSet(this, 0, 1)) {
            reportingTaskFuture = CommonPools.workerGroup().next().scheduleAtFixedRate(
                    this::reportException, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        if (thrownExceptions == null) {
            thrownExceptions = new HashMap<>();
        }

        thrownExceptions.compute(cause, (k, v) -> {
            if (v == null) {
                final LongAdder adder = new LongAdder();
                adder.increment();
                return new Pair(adder, ctx);
            } else {
                v.first().increment();
                return v;
            }
        });

        micrometerCounter.increment();
        counter.increment();
    }

    @Override
    public void serverStarting(Server server) throws Exception {}

    @Override
    public void serverStarted(Server server) throws Exception {}

    @Override
    public void serverStopping(Server server) throws Exception {}

    @Override
    public void serverStopped(Server server) throws Exception {
        if (reportingTaskFuture == null) {
            return;
        }

        reportingTaskFuture.cancel(true);
        reportingTaskFuture = null;
    }

    private void reportException() {
        final long totalExceptionsCount = counter.sum();
        final long newExceptionsCount = totalExceptionsCount - lastExceptionsCount;
        if (newExceptionsCount == 0) {
            return;
        }

        Map<Throwable, Pair<LongAdder, ServiceRequestContext>> exceptions = thrownExceptions;
        if (exceptions != null) {
            logger.warn("Observed {} exception(s) that didn't reach a LoggingService in the last {}. " +
                        "Please consider adding a LoggingService as the outermost decorator to get " +
                        "detailed error logs. Thrown exceptions: {}",
                        newExceptionsCount,
                        TextFormatter.elapsed(intervalMillis, TimeUnit.MILLISECONDS), exceptions.toString());
            thrownExceptions = null;
        } else {
            logger.warn("Observed {} exception(s) that didn't reach a LoggingService in the last {}. " +
                        "Please consider adding a LoggingService as the outermost decorator to get " +
                        "detailed error logs.",
                        newExceptionsCount, TextFormatter.elapsed(intervalMillis, TimeUnit.MILLISECONDS));
        }

        lastExceptionsCount = totalExceptionsCount;
    }

    @VisibleForTesting
    Map<Throwable, Pair<LongAdder, ServiceRequestContext>> getThrownExceptions() {
        return thrownExceptions;
    }
}

