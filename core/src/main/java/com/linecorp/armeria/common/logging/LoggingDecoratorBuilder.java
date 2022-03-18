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
package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Functions;

/**
 * Builds a new logging decorator.
 */
public abstract class LoggingDecoratorBuilder {

    private static final BiFunction<RequestContext, HttpHeaders, HttpHeaders> DEFAULT_HEADERS_SANITIZER =
            Functions.second();
    private static final BiFunction<RequestContext, Object, Object> DEFAULT_CONTENT_SANITIZER =
            Functions.second();
    private static final BiFunction<RequestContext, Throwable, Throwable> DEFAULT_CAUSE_SANITIZER =
            Functions.second();

    @Nullable
    private Logger logger;
    private LogLevel requestLogLevel = LogLevel.DEBUG;
    private Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper =
            log -> requestLogLevel();
    @Nullable
    private ResponseLogLevelMapper responseLogLevelMapper = null;

    private boolean isRequestLogLevelSet;
    private boolean isRequestLogLevelMapperSet;

    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            requestHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
            requestContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            requestTrailersSanitizer = DEFAULT_HEADERS_SANITIZER;

    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            responseHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
            responseContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private BiFunction<? super RequestContext, ? super Throwable, ? extends @Nullable Object>
            responseCauseSanitizer = DEFAULT_CAUSE_SANITIZER;
    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
            responseTrailersSanitizer = DEFAULT_HEADERS_SANITIZER;

    /**
     * Sets the {@link Logger} to use when logging.
     * If unset, a default {@link Logger} will be used.
     */
    public LoggingDecoratorBuilder logger(Logger logger) {
        this.logger = requireNonNull(logger, "logger");
        return this;
    }

    /**
     * Sets the name of the {@link Logger} to use when logging.
     * This method is a shortcut for {@code this.logger(LoggerFactory.getLogger(loggerName))}.
     */
    public LoggingDecoratorBuilder logger(String loggerName) {
        requireNonNull(loggerName, "loggerName");
        logger = LoggerFactory.getLogger(loggerName);
        return this;
    }

    /**
     * Returns the {@link Logger} the user specified to use,
     * or {@code null} if not set and a default logger should be used.
     */
    @Nullable
    protected final Logger logger() {
        return logger;
    }

    /**
     * Sets the {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#DEBUG}.
     */
    public LoggingDecoratorBuilder requestLogLevel(LogLevel requestLogLevel) {
        if (isRequestLogLevelMapperSet) {
            throw new IllegalStateException("requestLogLevelMapper has been set already.");
        }
        this.requestLogLevel = requireNonNull(requestLogLevel, "requestLogLevel");
        isRequestLogLevelSet = true;
        return this;
    }

    /**
     * Returns the {@link LogLevel} to use when logging requests.
     */
    @VisibleForTesting
    final LogLevel requestLogLevel() {
        return requestLogLevel;
    }

    /**
     * Sets the {@link LogLevel} to use when logging responses that's status is equal to the specified
     * {@link HttpStatus}.
     */
    public LoggingDecoratorBuilder responseLogLevel(HttpStatus status, LogLevel logLevel) {
        return responseLogLevelMapper(ResponseLogLevelMapper.of(status, logLevel));
    }

    /**
     * Sets the {@link LogLevel} to use when logging responses that's status is belong to the specified
     * {@link HttpStatusClass}.
     */
    public LoggingDecoratorBuilder responseLogLevel(HttpStatusClass statusClass, LogLevel logLevel) {
        return responseLogLevelMapper(ResponseLogLevelMapper.of(statusClass, logLevel));
    }

    /**
     * Sets the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * If unset, will use {@link LogLevel#DEBUG}.
     */
    public LoggingDecoratorBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        return responseLogLevelMapper(log -> log.responseCause() == null ? successfulResponseLogLevel : null);
    }

    /**
     * Sets the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * If unset, will use {@link LogLevel#WARN}. The request will be logged too if it was not otherwise.
     */
    public LoggingDecoratorBuilder failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
        return responseLogLevelMapper(log -> log.responseCause() != null ? failedResponseLogLevel : null);
    }

    /**
     * Sets the {@link Function} to use when mapping the log level of request logs.
     */
    public LoggingDecoratorBuilder requestLogLevelMapper(
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper) {
        if (isRequestLogLevelSet) {
            throw new IllegalStateException("requestLogLevel has been set already.");
        }
        this.requestLogLevelMapper = requireNonNull(requestLogLevelMapper, "requestLogLevelMapper");
        isRequestLogLevelMapperSet = true;
        return this;
    }

    /**
     * Returns the {@link LogLevel} to use when logging request logs.
     */
    protected final Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper() {
        return requestLogLevelMapper;
    }

    /**
     * Sets the {@link ResponseLogLevelMapper} to use when mapping the log level of response logs.
     */
    public LoggingDecoratorBuilder responseLogLevelMapper(ResponseLogLevelMapper responseLogLevelMapper) {
        requireNonNull(responseLogLevelMapper, "responseLogLevelMapper");
        if (this.responseLogLevelMapper == null) {
            this.responseLogLevelMapper = responseLogLevelMapper;
        } else {
            this.responseLogLevelMapper = this.responseLogLevelMapper.orElse(responseLogLevelMapper);
        }
        return this;
    }

    /**
     * Returns the {@link LogLevel} to use when logging response logs.
     */
    protected final ResponseLogLevelMapper responseLogLevelMapper() {
        if (responseLogLevelMapper == null) {
            return ResponseLogLevelMapper.of();
        }
        return responseLogLevelMapper.orElse(ResponseLogLevelMapper.of());
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will
     * not sanitize request headers.
     */
    public LoggingDecoratorBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer) {
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request headers before logging.
     */
    protected final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
    requestHeadersSanitizer() {
        return requestHeadersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will not sanitize response headers.
     */
    public LoggingDecoratorBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer) {
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response headers before logging.
     */
    protected final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
    responseHeadersSanitizer() {
        return responseHeadersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request trailers before logging. If unset,
     * will not sanitize request trailers.
     */
    public LoggingDecoratorBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer) {
        this.requestTrailersSanitizer = requireNonNull(requestTrailersSanitizer, "requestTrailersSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request trailers before logging.
     */
    protected final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable Object> requestTrailersSanitizer() {
        return requestTrailersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response trailers before logging. If unset,
     * will not sanitize response trailers.
     */
    public LoggingDecoratorBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer) {
        this.responseTrailersSanitizer = requireNonNull(responseTrailersSanitizer, "responseTrailersSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response trailers before logging.
     */
    protected final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
    responseTrailersSanitizer() {
        return responseTrailersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request, response and trailers before logging.
     * It is common to have the {@link BiFunction} that removes sensitive headers, like {@code "Cookie"} and
     * {@code "Set-Cookie"}, before logging. This method is a shortcut for:
     * <pre>{@code
     * builder.requestHeadersSanitizer(headersSanitizer);
     * builder.requestTrailersSanitizer(headersSanitizer);
     * builder.responseHeadersSanitizer(headersSanitizer);
     * builder.responseTrailersSanitizer(headersSanitizer);
     * }</pre>
     *
     * @see #requestHeadersSanitizer(BiFunction)
     * @see #requestTrailersSanitizer(BiFunction)
     * @see #responseHeadersSanitizer(BiFunction)
     * @see #responseTrailersSanitizer(BiFunction)
     */
    public LoggingDecoratorBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> headersSanitizer) {

        requireNonNull(headersSanitizer, "headersSanitizer");
        requestHeadersSanitizer(headersSanitizer);
        requestTrailersSanitizer(headersSanitizer);
        responseHeadersSanitizer(headersSanitizer);
        responseTrailersSanitizer(headersSanitizer);
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an GPS location query, before logging.
     * If unset, will not sanitize request content.
     */
    public LoggingDecoratorBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer) {
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request content before logging.
     */
    protected final BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
    requestContentSanitizer() {
        return requestContentSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset,
     * will not sanitize response content.
     */
    public LoggingDecoratorBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer) {
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response content before logging.
     */
    protected final BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
    responseContentSanitizer() {
        return responseContentSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request and response content before logging. It is common
     * to have the {@link BiFunction} that removes sensitive content, such as an GPS location query and
     * an address, before logging. If unset, will not sanitize content.
     * This method is a shortcut for:
     * <pre>{@code
     * builder.requestContentSanitizer(contentSanitizer);
     * builder.responseContentSanitizer(contentSanitizer);
     * }</pre>
     *
     * @see #requestContentSanitizer(BiFunction)
     * @see #responseContentSanitizer(BiFunction)
     */
    public LoggingDecoratorBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends @Nullable Object> contentSanitizer) {
        requireNonNull(contentSanitizer, "contentSanitizer");
        requestContentSanitizer(contentSanitizer);
        responseContentSanitizer(contentSanitizer);
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize a response cause before logging. You can
     * sanitize the stack trace of the exception to remove sensitive information, or prevent from logging
     * the stack trace completely by returning {@code null} in the {@link BiFunction}. If unset, will not
     * sanitize a response cause.
     */
    public LoggingDecoratorBuilder responseCauseSanitizer(
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer) {
        this.responseCauseSanitizer = requireNonNull(responseCauseSanitizer, "responseCauseSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response cause before logging.
     */
    protected final BiFunction<? super RequestContext, ? super Throwable, ? extends @Nullable Object>
    responseCauseSanitizer() {
        return responseCauseSanitizer;
    }

    @Override
    public String toString() {
        return toString(this, logger, requestLogLevel,
                        requestLogLevelMapper, responseLogLevelMapper, isRequestLogLevelMapperSet,
                        requestHeadersSanitizer, requestContentSanitizer, requestTrailersSanitizer,
                        responseHeadersSanitizer, responseContentSanitizer, responseTrailersSanitizer,
                        responseCauseSanitizer);
    }

    private static String toString(
            LoggingDecoratorBuilder self,
            @Nullable Logger logger,
            LogLevel requestLogLevel,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            boolean isRequestLogLevelMapperSet,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer,
            BiFunction<? super RequestContext, ?,
                    ? extends @Nullable Object> requestContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer,
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer) {

        final ToStringHelper helper = MoreObjects.toStringHelper(self)
                                                 .omitNullValues()
                                                 .add("logger", logger);

        if (isRequestLogLevelMapperSet) {
            helper.add("requestLogLevelMapper", requestLogLevelMapper);
        } else {
            helper.add("requestLogLevel", requestLogLevel);
        }

        helper.add("responseLogLevelMapper", responseLogLevelMapper);

        if (requestHeadersSanitizer != DEFAULT_HEADERS_SANITIZER) {
            helper.add("requestHeadersSanitizer", requestHeadersSanitizer);
        }
        if (requestContentSanitizer != DEFAULT_CONTENT_SANITIZER) {
            helper.add("requestContentSanitizer", requestContentSanitizer);
        }
        if (requestTrailersSanitizer != DEFAULT_HEADERS_SANITIZER) {
            helper.add("requestTrailersSanitizer", requestTrailersSanitizer);
        }

        if (responseHeadersSanitizer != DEFAULT_HEADERS_SANITIZER) {
            helper.add("responseHeadersSanitizer", responseHeadersSanitizer);
        }
        if (responseContentSanitizer != DEFAULT_CONTENT_SANITIZER) {
            helper.add("responseContentSanitizer", responseContentSanitizer);
        }
        if (responseTrailersSanitizer != DEFAULT_HEADERS_SANITIZER) {
            helper.add("responseTrailersSanitizer", responseTrailersSanitizer);
        }
        if (responseCauseSanitizer != DEFAULT_CAUSE_SANITIZER) {
            helper.add("responseCauseSanitizer", responseCauseSanitizer);
        }
        return helper.toString();
    }
}
