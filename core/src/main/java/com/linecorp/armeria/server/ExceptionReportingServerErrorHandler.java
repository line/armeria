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

/**
 * A {@link ServerErrorHandler} that wraps another {@link ServerErrorHandler}
 * to periodically report the exceptions that were not logged by decorators such as
 * {@link LoggingService}.
 */
class ExceptionReportingServerErrorHandler implements ServerErrorHandler, ServerListener {

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
        assert !duration.isNegative() && !duration.isZero();
        delegate = serverErrorHandler;
        this.duration = duration;
        counter = new LongAdder();
    }

    /**
     * If it's required to report exceptions, increase the counter and store first thrown exception.
     */
    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        if (ctx.shouldReportUnloggedException()) {
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

    @Override
    public void serverStarting(Server server) throws Exception {
        if (!started) {
            reportingTaskFuture = server.config().workerGroup().scheduleAtFixedRate(
                    this::reportException, duration.toMillis(), duration.toMillis(), TimeUnit.MILLISECONDS);
            started = true;
        }
    }

    @Override
    public void serverStarted(Server server) throws Exception {}

    @Override
    public void serverStopping(Server server) throws Exception {
        if (reportingTaskFuture != null) {
            reportingTaskFuture.cancel(true);
            reportingTaskFuture = null;
        }
        started = false;
    }

    @Override
    public void serverStopped(Server server) throws Exception {}

    /**
     * Returns the number of unlogged exceptions.
     */
    long unloggedExceptions() {
        return counter.sum();
    }

    private void reportException() {
        final long totalExceptions = counter.sumThenReset();
        if (totalExceptions == 0) {
            return;
        }

        final Throwable exception = thrownException;
        if (exception != null) {
            logger.warn("Observed {} uncaught exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs. " +
                        "One of the thrown exceptions:",
                        totalExceptions, TextFormatter.elapsed(duration.toNanos()), exception);
            thrownException = null;
        } else {
            logger.warn("Observed {} uncaught exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs.",
                        totalExceptions, TextFormatter.elapsed(duration.toNanos()));
        }
    }
}
