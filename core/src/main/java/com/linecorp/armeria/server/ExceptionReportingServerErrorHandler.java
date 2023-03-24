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
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * A {@link ServerErrorHandler} that wraps another {@link ServerErrorHandler}
 * to periodically report the exceptions that were not logged by decorators such as
 * {@link LoggingService}.
 */
final class ExceptionReportingServerErrorHandler implements ServerErrorHandler, ServerListener {

    private static final Logger logger = LoggerFactory.getLogger(ExceptionReportingServerErrorHandler.class);
    private static final AtomicIntegerFieldUpdater<ExceptionReportingServerErrorHandler> scheduledUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ExceptionReportingServerErrorHandler.class,
                                                 "scheduled");

    private final Duration interval;
    private final ServerErrorHandler delegate;
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
    private Throwable thrownException;

    ExceptionReportingServerErrorHandler(MeterRegistry meterRegistry, ServerErrorHandler serverErrorHandler,
                                         Duration interval) {
        assert !interval.isNegative() && !interval.isZero() : interval;
        this.interval = interval;
        delegate = serverErrorHandler;
        micrometerCounter = meterRegistry.counter("armeria.server.exceptions.unhandled");
        counter = new LongAdder();
    }

    /**
     * Increments both {@code micrometerCounter} and {@code counter} and stores unhandled exception
     * that occurs. If multiple servers use the same {@link MeterRegistry}, then {@code micrometerCounter}
     * will be shared across servers.
     */
    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        if (!ctx.shouldReportUnhandledExceptions()) {
            return delegate.onServiceException(ctx, cause);
        }

        if ((cause instanceof HttpStatusException || cause instanceof HttpResponseException) &&
            cause.getCause() != null) {
            return delegate.onServiceException(ctx, cause);
        }

        if (reportingTaskFuture == null && scheduledUpdater.compareAndSet(this, 0, 1)) {
            reportingTaskFuture = ctx.eventLoop().scheduleAtFixedRate(
                    this::reportException, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }

        if (thrownException == null) {
            thrownException = cause;
        }

        micrometerCounter.increment();
        counter.increment();

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

        final Throwable exception = thrownException;
        if (exception != null) {
            logger.warn("Observed {} unhandled exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs. " +
                        "One of the thrown exceptions:",
                        newExceptionsCount, TextFormatter.elapsed(interval.toNanos()), exception);
            thrownException = null;
        } else {
            logger.warn("Observed {} unhandled exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs.",
                        newExceptionsCount, TextFormatter.elapsed(interval.toNanos()));
        }

        lastExceptionsCount = totalExceptionsCount;
    }
}
