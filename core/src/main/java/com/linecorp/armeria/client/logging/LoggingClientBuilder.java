/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.logging;

import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Builds a new {@link LoggingClient}.
 */
public final class LoggingClientBuilder extends AbstractLoggingClientBuilder {

    LoggingClientBuilder() {}

    /**
     * Returns a newly-created {@link LoggingClient} decorating {@code delegate} based on the properties of
     * this builder.
     */
    public LoggingClient build(HttpClient delegate) {
        return new LoggingClient(delegate,
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
                                 successSampler(),
                                 failureSampler());
    }

    /**
     * Returns a newly-created {@link LoggingClient} decorator based on the properties of this builder.
     */
    public Function<? super HttpClient, LoggingClient> newDecorator() {
        return this::build;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public LoggingClientBuilder samplingRate(float samplingRate) {
        return (LoggingClientBuilder) super.samplingRate(samplingRate);
    }

    @Override
    public LoggingClientBuilder sampler(Sampler<? super ClientRequestContext> sampler) {
        return (LoggingClientBuilder) super.sampler(sampler);
    }

    @Override
    public LoggingClientBuilder successSamplingRate(float samplingRate) {
        return (LoggingClientBuilder) super.successSamplingRate(samplingRate);
    }

    @Override
    public LoggingClientBuilder successSampler(Sampler<? super ClientRequestContext> sampler) {
        return (LoggingClientBuilder) super.successSampler(sampler);
    }

    @Override
    public LoggingClientBuilder failureSamplingRate(float samplingRate) {
        return (LoggingClientBuilder) super.failureSamplingRate(samplingRate);
    }

    @Override
    public LoggingClientBuilder failureSampler(Sampler<? super ClientRequestContext> sampler) {
        return (LoggingClientBuilder) super.failureSampler(sampler);
    }

    // Override the return type of the chaining methods in the super-superclass.

    @Override
    public LoggingClientBuilder logger(Logger logger) {
        return (LoggingClientBuilder) super.logger(logger);
    }

    @Override
    public LoggingClientBuilder logger(String loggerName) {
        return (LoggingClientBuilder) super.logger(loggerName);
    }

    @Override
    public LoggingClientBuilder requestLogLevel(LogLevel requestLogLevel) {
        return (LoggingClientBuilder) super.requestLogLevel(requestLogLevel);
    }

    @Override
    public LoggingClientBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        return (LoggingClientBuilder) super.successfulResponseLogLevel(successfulResponseLogLevel);
    }

    @Override
    public LoggingClientBuilder failureResponseLogLevel(LogLevel failureResponseLogLevel) {
        return (LoggingClientBuilder) super.failureResponseLogLevel(failureResponseLogLevel);
    }

    @Override
    public LoggingClientBuilder requestLogLevelMapper(
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper) {
        return (LoggingClientBuilder) super.requestLogLevelMapper(requestLogLevelMapper);
    }

    @Override
    public LoggingClientBuilder responseLogLevelMapper(
            Function<? super RequestLog, LogLevel> responseLogLevelMapper) {
        return (LoggingClientBuilder) super.responseLogLevelMapper(responseLogLevelMapper);
    }

    @Override
    public LoggingClientBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer) {
        return (LoggingClientBuilder) super.requestHeadersSanitizer(requestHeadersSanitizer);
    }

    @Override
    public LoggingClientBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer) {
        return (LoggingClientBuilder) super.responseHeadersSanitizer(responseHeadersSanitizer);
    }

    @Override
    public LoggingClientBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer) {
        return (LoggingClientBuilder) super.requestTrailersSanitizer(requestTrailersSanitizer);
    }

    @Override
    public LoggingClientBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer) {
        return (LoggingClientBuilder) super.responseTrailersSanitizer(responseTrailersSanitizer);
    }

    @Override
    public LoggingClientBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> headersSanitizer) {
        return (LoggingClientBuilder) super.headersSanitizer(headersSanitizer);
    }

    @Override
    public LoggingClientBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer) {
        return (LoggingClientBuilder) super.requestContentSanitizer(requestContentSanitizer);
    }

    @Override
    public LoggingClientBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer) {
        return (LoggingClientBuilder) super.responseContentSanitizer(responseContentSanitizer);
    }

    @Override
    public LoggingClientBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer) {
        return (LoggingClientBuilder) super.contentSanitizer(contentSanitizer);
    }

    @Override
    public LoggingClientBuilder responseCauseSanitizer(
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer) {
        return (LoggingClientBuilder) super.responseCauseSanitizer(responseCauseSanitizer);
    }
}
