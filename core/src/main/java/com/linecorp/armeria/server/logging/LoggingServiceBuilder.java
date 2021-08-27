/*
 * Copyright 2019 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LoggingDecoratorBuilder;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link LoggingService}.
 */
public final class LoggingServiceBuilder extends LoggingDecoratorBuilder {

    private Sampler<? super ServiceRequestContext> sampler = Sampler.always();

    LoggingServiceBuilder() {}

    /**
     * Sets the {@link Sampler} that determines which request needs logging.
     */
    public LoggingServiceBuilder sampler(Sampler<? super ServiceRequestContext> sampler) {
        this.sampler = requireNonNull(sampler, "sampler");
        return this;
    }

    /**
     * Sets the rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged.
     */
    public LoggingServiceBuilder samplingRate(float samplingRate) {
        checkArgument(0.0 <= samplingRate && samplingRate <= 1.0,
                      "samplingRate: %s (expected: 0.0 <= samplingRate <= 1.0)", samplingRate);
        return sampler(Sampler.random(samplingRate));
    }

    /**
     * Returns a newly-created {@link LoggingService} decorating {@link HttpService} based on the properties
     * of this builder.
     */
    public LoggingService build(HttpService delegate) {
        return new LoggingService(delegate,
                                  logger(),
                                  requestLogLevelMapper(),
                                  responseLogLevelMapper(),
                                  requestHeadersSanitizer(),
                                  requestContentSanitizer(),
                                  requestTrailersSanitizer(),
                                  responseHeadersSanitizer(),
                                  responseContentSanitizer(),
                                  responseTrailersSanitizer(),
                                  responseCauseSanitizer(),
                                  sampler);
    }

    /**
     * Returns a newly-created {@link LoggingService} decorator based on the properties of this builder.
     */
    public Function<? super HttpService, LoggingService> newDecorator() {
        return this::build;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public LoggingServiceBuilder logger(Logger logger) {
        return (LoggingServiceBuilder) super.logger(logger);
    }

    @Override
    public LoggingServiceBuilder logger(String loggerName) {
        return (LoggingServiceBuilder) super.logger(loggerName);
    }

    @Override
    public LoggingServiceBuilder requestLogLevel(LogLevel requestLogLevel) {
        return (LoggingServiceBuilder) super.requestLogLevel(requestLogLevel);
    }

    @Override
    public LoggingServiceBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        return (LoggingServiceBuilder) super.successfulResponseLogLevel(successfulResponseLogLevel);
    }

    @Override
    public LoggingServiceBuilder failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        return (LoggingServiceBuilder) super.failureResponseLogLevel(failedResponseLogLevel);
    }

    @Override
    public LoggingServiceBuilder requestLogLevelMapper(
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper) {
        return (LoggingServiceBuilder) super.requestLogLevelMapper(requestLogLevelMapper);
    }

    @Override
    public LoggingServiceBuilder responseLogLevelMapper(
            Function<? super RequestLog, LogLevel> responseLogLevelMapper) {
        return (LoggingServiceBuilder) super.responseLogLevelMapper(responseLogLevelMapper);
    }

    @Override
    public LoggingServiceBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer) {
        return (LoggingServiceBuilder) super.requestHeadersSanitizer(requestHeadersSanitizer);
    }

    @Override
    public LoggingServiceBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer) {
        return (LoggingServiceBuilder) super.responseHeadersSanitizer(responseHeadersSanitizer);
    }

    @Override
    public LoggingServiceBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer) {
        return (LoggingServiceBuilder) super.requestTrailersSanitizer(requestTrailersSanitizer);
    }

    @Override
    public LoggingServiceBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer) {
        return (LoggingServiceBuilder) super.responseTrailersSanitizer(responseTrailersSanitizer);
    }

    @Override
    public LoggingServiceBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> headersSanitizer) {
        return (LoggingServiceBuilder) super.headersSanitizer(headersSanitizer);
    }

    @Override
    public LoggingServiceBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer) {
        return (LoggingServiceBuilder) super.requestContentSanitizer(requestContentSanitizer);
    }

    @Override
    public LoggingServiceBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer) {
        return (LoggingServiceBuilder) super.responseContentSanitizer(responseContentSanitizer);
    }

    @Override
    public LoggingServiceBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer) {
        return (LoggingServiceBuilder) super.contentSanitizer(contentSanitizer);
    }

    @Override
    public LoggingServiceBuilder responseCauseSanitizer(
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer) {
        return (LoggingServiceBuilder) super.responseCauseSanitizer(responseCauseSanitizer);
    }
}
