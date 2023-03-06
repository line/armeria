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

import io.micrometer.core.instrument.Tags;

/**
 * A {@link ServerErrorHandler} that wraps another {@link ServerErrorHandler}
 * to periodically report the exceptions that were not logged by decorators such as
 * {@link LoggingService}.
 */
final class ExceptionReportingServerErrorHandler implements ServerErrorHandler, ServerListener {

    private static final Logger logger = LoggerFactory.getLogger(ExceptionReportingServerErrorHandler.class);

    @Nullable
    private static ExceptionReportingServerErrorHandler exceptionReportingServerErrorHandler;
    private final ServerErrorHandler delegate;
    private final Duration duration;
    private final LongAdder counter;
    private long lastExceptionsCount;
    @Nullable
    private Throwable thrownException;
    @Nullable
    private ScheduledFuture<?> reportingTaskFuture;

    private ExceptionReportingServerErrorHandler(ServerErrorHandler serverErrorHandler, Duration duration) {
        assert !duration.isNegative() && !duration.isZero();
        delegate = serverErrorHandler;
        this.duration = duration;
        counter = new LongAdder();
    }

    static ExceptionReportingServerErrorHandler of(ServerErrorHandler serverErrorHandler, Duration duration) {
        if (exceptionReportingServerErrorHandler != null) {
            return exceptionReportingServerErrorHandler;
        }

        exceptionReportingServerErrorHandler = new ExceptionReportingServerErrorHandler(serverErrorHandler,
                                                                                        duration);
        return exceptionReportingServerErrorHandler;
    }

    /**
     * This method increments the {@link #counter} and stores unhandled exception that occurs. If unhandled
     * exceptions are not previously scheduled for reporting, then they are scheduled to be reported.
     */
    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        final boolean isExpectedException =
                (cause instanceof HttpStatusException || cause instanceof HttpResponseException) &&
                cause.getCause() != null;

        if (ctx.shouldReportUnhandledExceptions() && !isExpectedException) {
            if (reportingTaskFuture == null) {
                reportingTaskFuture = ctx.eventLoop().scheduleAtFixedRate(
                        this::reportException, duration.toMillis(), duration.toMillis(), TimeUnit.MILLISECONDS);
            }

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
    public void serverStarting(Server server) throws Exception {}

    @Override
    public void serverStarted(Server server) throws Exception {
        server.config().meterRegistry().more().counter("armeria.server.unhandledExceptions",
                                                       Tags.empty(), counter, LongAdder::sum);
    }

    @Override
    public void serverStopping(Server server) throws Exception {
        if (reportingTaskFuture != null) {
            reportingTaskFuture.cancel(true);
            reportingTaskFuture = null;
        }

        exceptionReportingServerErrorHandler = null;
    }

    @Override
    public void serverStopped(Server server) throws Exception {}

    private void reportException() {
        final long totalExceptionsCount = counter.sum();
        final long newExceptionsCount = totalExceptionsCount - lastExceptionsCount;
        if (newExceptionsCount == 0) {
            return;
        }

        final Throwable exception = thrownException;
        if (exception != null) {
            logger.warn("Observed {} unhandled exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs. " +
                        "One of the thrown exceptions:",
                        newExceptionsCount, TextFormatter.elapsed(duration.toNanos()), exception);
            thrownException = null;
        } else {
            logger.warn("Observed {} unhandled exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs.",
                        newExceptionsCount, TextFormatter.elapsed(duration.toNanos()));
        }

        lastExceptionsCount = totalExceptionsCount;
    }
}
