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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.channel.EventLoopGroup;

/**
 * Implementation of {@link ServerErrorHandler} that is used to decorate other ServerErrorHandlers.
 * When services are not annotated with {@link LoggingService}, exceptions are not caught be default.
 * {@link UncaughtExceptionsServerErrorHandler} is used to catch those uncaught exceptions.
 */
public class UncaughtExceptionsServerErrorHandler implements ServerErrorHandler {

    private static final Logger logger = LoggerFactory.getLogger(UncaughtExceptionsServerErrorHandler.class);
    private final ServerErrorHandler delegate;
    private final LongAdder counter;
    private final long intervalInSeconds;
    private boolean isScheduled;
    @Nullable
    private Throwable lastThrownException;
    @Nullable
    private ScheduledFuture<?> reportUncaughtExceptionsSchedule;

    UncaughtExceptionsServerErrorHandler(ServerErrorHandler serverErrorHandler, long intervalInSeconds) {
        delegate = serverErrorHandler;
        this.intervalInSeconds = intervalInSeconds;
        counter = new LongAdder();
    }

    /**
     * If it's required to log uncaught exceptions, increase the counter and store last thrown exception.
     */
    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        if (isScheduled && ctx.shouldLogUncaughtExceptions()) {
            counter.increment();
            lastThrownException = cause;
        }
        return delegate.onServiceException(ctx, cause);
    }

    @Nullable
    @Override
    public AggregatedHttpResponse renderStatus(ServiceConfig config, @Nullable RequestHeaders headers,
                                               HttpStatus status, @Nullable String description,
                                               @Nullable Throwable cause) {
        return delegate.renderStatus(config, headers, status, description, cause);
    }

    /**
     * Schedules uncaught exceptions logging.
     * @param workerGroup eventLoopGroup on which logging will be scheduled.
     */
    public void scheduleLogging(EventLoopGroup workerGroup) {
        requireNonNull(workerGroup, "workerGroup");
        if (!isScheduled) {
            reportUncaughtExceptionsSchedule = workerGroup.scheduleAtFixedRate(
                    this::logUncaughtExceptions, intervalInSeconds, intervalInSeconds, TimeUnit.SECONDS);
            isScheduled = true;
        }
    }

    /**
     * Unschedule uncaught exceptions logging.
     */
    public void unScheduleLogging() {
        if (isScheduled && reportUncaughtExceptionsSchedule != null) {
            reportUncaughtExceptionsSchedule.cancel(true);
            isScheduled = false;
        }
    }

    private void logUncaughtExceptions() {
        logger.warn("Observed {} uncaught exceptions in last {} seconds. " +
                    "If you don't see error logs please use LoggingService decorator.",
                    counter.sumThenReset(), intervalInSeconds);
        if (lastThrownException != null) {
            logger.warn("Last thrown exception is: ", lastThrownException);
        }
        lastThrownException = null;
    }
}
