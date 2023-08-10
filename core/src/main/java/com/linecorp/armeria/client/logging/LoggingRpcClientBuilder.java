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
package com.linecorp.armeria.client.logging;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogLevelMapper;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.logging.ResponseLogLevelMapper;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Builds a new {@link LoggingRpcClient}.
 */
public final class LoggingRpcClientBuilder extends AbstractLoggingClientBuilder {

    /**
     * Creates a new instance.
     */
    LoggingRpcClientBuilder() {}

    /**
     * Returns a newly-created {@link LoggingRpcClient} decorating {@code delegate} based on the properties of
     * this builder.
     */
    public LoggingRpcClient build(RpcClient delegate) {
        return new LoggingRpcClient(delegate, logWriter(), successSampler(), failureSampler());
    }

    /**
     * Returns a newly-created {@link LoggingRpcClient} decorator based on the properties of this builder.
     */
    public Function<? super RpcClient, LoggingRpcClient> newDecorator() {
        return this::build;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    protected LoggingRpcClientBuilder defaultLogger(Logger logger) {
        return (LoggingRpcClientBuilder) super.defaultLogger(logger);
    }

    @Override
    public LoggingRpcClientBuilder sampler(Sampler<? super ClientRequestContext> sampler) {
        return (LoggingRpcClientBuilder) super.sampler(sampler);
    }

    @Override
    public LoggingRpcClientBuilder samplingRate(float samplingRate) {
        return (LoggingRpcClientBuilder) super.samplingRate(samplingRate);
    }

    @Override
    public LoggingRpcClientBuilder successSampler(Sampler<? super ClientRequestContext> sampler) {
        return (LoggingRpcClientBuilder) super.successSampler(sampler);
    }

    @Override
    public LoggingRpcClientBuilder successSamplingRate(float samplingRate) {
        return (LoggingRpcClientBuilder) super.successSamplingRate(samplingRate);
    }

    @Override
    public LoggingRpcClientBuilder failureSampler(Sampler<? super ClientRequestContext> sampler) {
        return (LoggingRpcClientBuilder) super.failureSampler(sampler);
    }

    @Override
    public LoggingRpcClientBuilder failureSamplingRate(float samplingRate) {
        return (LoggingRpcClientBuilder) super.failureSamplingRate(samplingRate);
    }

    // Override the return type of the chaining methods in the super-superclass.

    @Override
    public LoggingRpcClientBuilder logger(Logger logger) {
        return (LoggingRpcClientBuilder) super.logger(logger);
    }

    @Override
    public LoggingRpcClientBuilder logger(String loggerName) {
        return (LoggingRpcClientBuilder) super.logger(loggerName);
    }

    @Override
    public LoggingRpcClientBuilder requestLogLevel(LogLevel requestLogLevel) {
        return (LoggingRpcClientBuilder) super.requestLogLevel(requestLogLevel);
    }

    @Override
    public LoggingRpcClientBuilder requestLogLevel(Class<? extends Throwable> clazz, LogLevel requestLogLevel) {
        return (LoggingRpcClientBuilder) super.requestLogLevel(clazz, requestLogLevel);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder requestLogLevelMapper(
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper) {
        return (LoggingRpcClientBuilder) super.requestLogLevelMapper(requestLogLevelMapper);
    }

    @Override
    public LoggingRpcClientBuilder requestLogLevelMapper(RequestLogLevelMapper requestLogLevelMapper) {
        return (LoggingRpcClientBuilder) super.requestLogLevelMapper(requestLogLevelMapper);
    }

    @Override
    public LoggingRpcClientBuilder responseLogLevel(HttpStatus status, LogLevel logLevel) {
        return (LoggingRpcClientBuilder) super.responseLogLevel(status, logLevel);
    }

    @Override
    public LoggingRpcClientBuilder responseLogLevel(HttpStatusClass statusClass, LogLevel logLevel) {
        return (LoggingRpcClientBuilder) super.responseLogLevel(statusClass, logLevel);
    }

    @Override
    public LoggingRpcClientBuilder responseLogLevel(Class<? extends Throwable> clazz, LogLevel logLevel) {
        return (LoggingRpcClientBuilder) super.responseLogLevel(clazz, logLevel);
    }

    @Override
    public LoggingRpcClientBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        return (LoggingRpcClientBuilder) super.successfulResponseLogLevel(successfulResponseLogLevel);
    }

    @Override
    public LoggingRpcClientBuilder failureResponseLogLevel(LogLevel failureResponseLogLevel) {
        return (LoggingRpcClientBuilder) super.failureResponseLogLevel(failureResponseLogLevel);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder responseLogLevelMapper(
            Function<? super RequestLog, LogLevel> responseLogLevelMapper) {
        return (LoggingRpcClientBuilder) super.responseLogLevelMapper(responseLogLevelMapper);
    }

    @Override
    public LoggingRpcClientBuilder responseLogLevelMapper(ResponseLogLevelMapper responseLogLevelMapper) {
        return (LoggingRpcClientBuilder) super.responseLogLevelMapper(responseLogLevelMapper);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer) {
        return (LoggingRpcClientBuilder) super.requestHeadersSanitizer(requestHeadersSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer) {
        return (LoggingRpcClientBuilder) super.responseHeadersSanitizer(responseHeadersSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer) {
        return (LoggingRpcClientBuilder) super.requestTrailersSanitizer(requestTrailersSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer) {
        return (LoggingRpcClientBuilder) super.responseTrailersSanitizer(responseTrailersSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> headersSanitizer) {
        return (LoggingRpcClientBuilder) super.headersSanitizer(headersSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer) {
        return (LoggingRpcClientBuilder) super.requestContentSanitizer(requestContentSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer) {
        return (LoggingRpcClientBuilder) super.responseContentSanitizer(responseContentSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends @Nullable Object> contentSanitizer) {
        return (LoggingRpcClientBuilder) super.contentSanitizer(contentSanitizer);
    }

    @Deprecated
    @Override
    public LoggingRpcClientBuilder responseCauseSanitizer(
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer) {
        return (LoggingRpcClientBuilder) super.responseCauseSanitizer(responseCauseSanitizer);
    }

    @Override
    public LoggingRpcClientBuilder responseCauseFilter(Predicate<Throwable> responseCauseFilter) {
        return (LoggingRpcClientBuilder) super.responseCauseFilter(responseCauseFilter);
    }

    @Override
    public LoggingRpcClientBuilder logWriter(LogWriter logWriter) {
        return (LoggingRpcClientBuilder) super.logWriter(logWriter);
    }
}
