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

import java.time.Duration;
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
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.channel.EventLoopGroup;

/**
 * A {@link ServerErrorHandler} that wraps another {@link ServerErrorHandler}
 * to periodically report the exceptions that were not logged by decorators such as
 * {@link LoggingService}.
 */
class ExceptionReportingServerErrorHandler implements ServerErrorHandler {
    private static final Logger logger = LoggerFactory.getLogger(ExceptionReportingServerErrorHandler.class);
    private final ServerErrorHandler delegate;
    private final LongAdder counter;
    private final Duration duration;
    private boolean started;
    @Nullable
    private Throwable thrownException;
    @Nullable
    private ScheduledFuture<?> reportingTaskFuture;

    ExceptionReportingServerErrorHandler(ServerErrorHandler serverErrorHandler, Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Duration " +
                                               TextFormatter.elapsed(duration.toNanos()) +
                                               " (expected: > 0)");
        }
        delegate = serverErrorHandler;
        this.duration = duration;
        counter = new LongAdder();
    }

    /**
     * If it's required to report exceptions, increase the counter and store last thrown exception.
     */
    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        if (ctx.shouldReportUnLoggedException()) {
            if (thrownException == null) {
                thrownException = cause;
            }
            counter.increment();
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
     * Starts reporting exceptions.
     * @param workerGroup eventLoopGroup on which reporting exceptions will be scheduled.
     */
    void start(EventLoopGroup workerGroup) {
        requireNonNull(workerGroup, "workerGroup");
        if (!started) {
            reportingTaskFuture = workerGroup.scheduleAtFixedRate(
                    this::reportException, duration.getSeconds(), duration.getSeconds(), TimeUnit.SECONDS);
            started = true;
        }
    }

    /**
     * Stops logging exceptions.
     */
    void stop() {
        if (started && reportingTaskFuture != null) {
            reportingTaskFuture.cancel(true);
            reportingTaskFuture = null;
            started = false;
        }
    }

    private void reportException() {
        final long totalExceptions = counter.sumThenReset();
        if (totalExceptions == 0) {
            return;
        }

        final Throwable exception = this.thrownException;
        if (exception != null) {
            logger.warn("Observed {} uncaught exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs. " +
                        "One of the thrown exceptions:",
                        totalExceptions, TextFormatter.elapsed(duration.toNanos()), exception);
            this.thrownException = null;
        } else {
            logger.warn("Observed {} uncaught exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs.",
                        totalExceptions, TextFormatter.elapsed(duration.toNanos()));
        }
    }
}
