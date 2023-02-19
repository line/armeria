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

package com.linecorp.armeria.server.logging;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoopGroup;

/**
 * Log uncaught exceptions. Uncaught exceptions are exceptions thrown by services
 * which have not been decorated with {@link LoggingService}.
 */
public final class UncaughtExceptionsLogger {
    private static final Logger logger = LoggerFactory.getLogger(UncaughtExceptionsLogger.class);
    private static boolean initialized;
    @Nullable private static ScheduledFuture<?> reportUncaughtExceptionsSchedule;
    @Nullable private static LongAdder counter;
    private static long logUncaughtExceptionsIntervalInSeconds = 60;
    @Nullable private static Throwable lastThrownException;

    private UncaughtExceptionsLogger() {}

    /**
     * Schedules uncaught exceptions logging.
     * @param workerGroup eventLoopGroup on which logging will be scheduled.
     * @param interval interval between logging in seconds.
     */
    public static void schedule(EventLoopGroup workerGroup, long interval) {
        requireNonNull(workerGroup, "workerGroup");
        if (!initialized) {
            UncaughtExceptionsLogger.initialize(interval);
        }
        reportUncaughtExceptionsSchedule = workerGroup.scheduleAtFixedRate(
                UncaughtExceptionsLogger::logUncaughtExceptions,
                interval,
                interval,
                TimeUnit.SECONDS);
    }

    /**
     * Unschedule uncaught exceptions logging.
     * @throws IllegalStateException if no schedule exists.
     */
    public static void unSchedule() {
        if (reportUncaughtExceptionsSchedule == null) {
            throw new IllegalStateException("UncaughtExceptionsLogger is not scheduled.");
        }
        reportUncaughtExceptionsSchedule.cancel(true);
    }

    private static void initialize(long seconds) {
        if (initialized) {
            return;
        }
        counter = new LongAdder();
        logUncaughtExceptionsIntervalInSeconds = seconds;
        initialized = true;
    }

    /**
     * Increments number of uncaught exceptions and stores last thrown exception.
     * @param throwable uncaught exception.
     */
    public static void handleUncaughtExceptions(Throwable throwable) {
        if (!initialized) {
            throw new IllegalStateException("UncaughtExceptionLogger is not initialized.");
        }
        requireNonNull(throwable, "throwable");
        counter.increment();
        lastThrownException = throwable;
    }

    private static void logUncaughtExceptions() {
        logger.warn("Observed {} uncaught exceptions in last {} seconds. " +
                    "If you don't see error logs please use LoggingService decorator.",
                    counter.sum(), logUncaughtExceptionsIntervalInSeconds);
        if (lastThrownException == null) {
            return;
        }
        logger.warn("Last exception occurred with following message: {}", lastThrownException.getMessage());

        counter.reset();
        lastThrownException = null;
    }
}
