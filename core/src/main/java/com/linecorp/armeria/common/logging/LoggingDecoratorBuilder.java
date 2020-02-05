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

import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.HttpHeaders;

/**
 * Builds a new logging decorator.
 */
public abstract class LoggingDecoratorBuilder {

    private static final Function<HttpHeaders, HttpHeaders> DEFAULT_HEADERS_SANITIZER = Function.identity();
    private static final Function<Object, Object> DEFAULT_CONTENT_SANITIZER = Function.identity();
    private static final Function<Throwable, Throwable> DEFAULT_CAUSE_SANITIZER = Function.identity();

    @Nullable
    private Logger logger;
    private LogLevel requestLogLevel = LogLevel.TRACE;
    private LogLevel successfulResponseLogLevel = LogLevel.TRACE;
    private LogLevel failedResponseLogLevel = LogLevel.WARN;
    private Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper =
            log -> requestLogLevel();
    private Function<? super RequestLog, LogLevel> responseLogLevelMapper =
            log -> log.responseCause() == null ? successfulResponseLogLevel() : failedResponseLogLevel();
    private boolean isRequestLogLevelSet;
    private boolean isResponseLogLevelSet;
    private boolean isRequestLogLevelMapperSet;
    private boolean isResponseLogLevelMapperSet;
    private Function<? super HttpHeaders, ?> requestHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private Function<Object, ?> requestContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Function<? super HttpHeaders, ?> requestTrailersSanitizer = DEFAULT_HEADERS_SANITIZER;

    private Function<? super HttpHeaders, ?> responseHeadersSanitizer = DEFAULT_HEADERS_SANITIZER;
    private Function<Object, ?> responseContentSanitizer = DEFAULT_CONTENT_SANITIZER;
    private Function<? super Throwable, ?> responseCauseSanitizer = DEFAULT_CAUSE_SANITIZER;
    private Function<? super HttpHeaders, ?> responseTrailersSanitizer = DEFAULT_HEADERS_SANITIZER;

    /**
     * Sets the {@link Logger} to use when logging.
     * If unset, a default {@link Logger} will be used.
     */
    public LoggingDecoratorBuilder logger(Logger logger) {
        this.logger = requireNonNull(logger, "logger");
        return this;
    }

    /**
     * Returns the {@link Logger} the user specified to use,
     * or {@code null} if not set and a default logger should be used.
     */
    @Nullable
    protected Logger logger() {
        return logger;
    }

    /**
     * Sets the {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#TRACE}.
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
     *
     * @deprecated It will be removed in the future.
     */
    @Deprecated
    protected LogLevel requestLogLevel() {
        return requestLogLevel;
    }

    /**
     * Sets the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * If unset, will use {@link LogLevel#TRACE}.
     */
    public LoggingDecoratorBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        if (isResponseLogLevelMapperSet) {
            throw new IllegalStateException("responseLogLevelMapper has been set already.");
        }
        this.successfulResponseLogLevel =
                requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        isResponseLogLevelSet = true;
        return this;
    }

    /**
     * Returns the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     *
     * @deprecated It will be removed in the future.
     */
    @Deprecated
    protected LogLevel successfulResponseLogLevel() {
        return successfulResponseLogLevel;
    }

    /**
     * Sets the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * If unset, will use {@link LogLevel#WARN}. The request will be logged too if it was not otherwise.
     */
    public LoggingDecoratorBuilder failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        if (isResponseLogLevelMapperSet) {
            throw new IllegalStateException("responseLogLevelMapper has been set already.");
        }
        this.failedResponseLogLevel = requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
        isResponseLogLevelSet = true;
        return this;
    }

    /**
     * Returns the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     *
     * @deprecated It will be removed in the future.
     */
    @Deprecated
    protected LogLevel failedResponseLogLevel() {
        return failedResponseLogLevel;
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
    protected Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper() {
        return requestLogLevelMapper;
    }

    /**
     * Sets the {@link Function} to use when mapping the log level of response logs.
     */
    public LoggingDecoratorBuilder responseLogLevelMapper(
            Function<? super RequestLog, LogLevel> responseLogLevelMapper) {
        if (isResponseLogLevelSet) {
            throw new IllegalStateException(
                    "successfulResponseLogLevel or failedResponseLogLevel has been set already.");
        }
        this.responseLogLevelMapper = requireNonNull(responseLogLevelMapper, "responseLogLevelMapper");
        isResponseLogLevelMapperSet = true;
        return this;
    }

    /**
     * Returns the {@link LogLevel} to use when logging response logs.
     */
    protected Function<? super RequestLog, LogLevel> responseLogLevelMapper() {
        return responseLogLevelMapper;
    }

    /**
     * Sets the {@link Function} to use to sanitize request headers before logging. It is common to have the
     * {@link Function} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will use
     * {@link Function#identity()}.
     */
    public LoggingDecoratorBuilder requestHeadersSanitizer(
            Function<? super HttpHeaders, ?> requestHeadersSanitizer) {
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize request headers before logging.
     */
    protected Function<? super HttpHeaders, ?> requestHeadersSanitizer() {
        return requestHeadersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize response headers before logging. It is common to have the
     * {@link Function} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingDecoratorBuilder responseHeadersSanitizer(
            Function<? super HttpHeaders, ?> responseHeadersSanitizer) {
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response headers before logging.
     */
    protected Function<? super HttpHeaders, ?> responseHeadersSanitizer() {
        return responseHeadersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize request trailers before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingDecoratorBuilder requestTrailersSanitizer(
            Function<? super HttpHeaders, ?> requestTrailersSanitizer) {
        this.requestTrailersSanitizer = requireNonNull(requestTrailersSanitizer, "requestTrailersSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize request trailers before logging.
     */
    protected Function<? super HttpHeaders, ?> requestTrailersSanitizer() {
        return requestTrailersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize response trailers before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingDecoratorBuilder responseTrailersSanitizer(
            Function<? super HttpHeaders, ?> responseTrailersSanitizer) {
        this.responseTrailersSanitizer = requireNonNull(responseTrailersSanitizer, "responseTrailersSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response trailers before logging.
     */
    protected Function<? super HttpHeaders, ?> responseTrailersSanitizer() {
        return responseTrailersSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize request, response and trailers before logging.
     * It is common to have the {@link Function} that removes sensitive headers, like {@code "Cookie"} and
     * {@code "Set-Cookie"}, before logging. This method is a shortcut for:
     * <pre>{@code
     * builder.requestHeadersSanitizer(headersSanitizer);
     * builder.requestTrailersSanitizer(headersSanitizer);
     * builder.responseHeadersSanitizer(headersSanitizer);
     * builder.responseTrailersSanitizer(headersSanitizer);
     * }</pre>
     *
     * @see #requestHeadersSanitizer(Function)
     * @see #requestTrailersSanitizer(Function)
     * @see #responseHeadersSanitizer(Function)
     * @see #responseTrailersSanitizer(Function)
     */
    public LoggingDecoratorBuilder headersSanitizer(Function<? super HttpHeaders, ?> headersSanitizer) {
        requireNonNull(headersSanitizer, "headersSanitizer");
        requestHeadersSanitizer(headersSanitizer);
        requestTrailersSanitizer(headersSanitizer);
        responseHeadersSanitizer(headersSanitizer);
        responseTrailersSanitizer(headersSanitizer);
        return this;
    }

    /**
     * Sets the {@link Function} to use to sanitize request content before logging. It is common to have the
     * {@link Function} that removes sensitive content, such as an GPS location query, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingDecoratorBuilder requestContentSanitizer(Function<Object, ?> requestContentSanitizer) {
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize request content before logging.
     */
    protected Function<Object, ?> requestContentSanitizer() {
        return requestContentSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize response content before logging. It is common to have the
     * {@link Function} that removes sensitive content, such as an address, before logging. If unset,
     * will use {@link Function#identity()}.
     */
    public LoggingDecoratorBuilder responseContentSanitizer(Function<Object, ?> responseContentSanitizer) {
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response content before logging.
     */
    protected Function<Object, ?> responseContentSanitizer() {
        return responseContentSanitizer;
    }

    /**
     * Sets the {@link Function} to use to sanitize request and response content before logging. It is common
     * to have the {@link Function} that removes sensitive content, such as an GPS location query and
     * an address, before logging. If unset, will use {@link Function#identity()}.
     * This method is a shortcut for:
     * <pre>{@code
     * builder.requestContentSanitizer(contentSanitizer);
     * builder.responseContentSanitizer(contentSanitizer);
     * }</pre>
     *
     * @see #requestContentSanitizer(Function)
     * @see #responseContentSanitizer(Function)
     */
    public LoggingDecoratorBuilder contentSanitizer(Function<Object, ?> contentSanitizer) {
        requireNonNull(contentSanitizer, "contentSanitizer");
        requestContentSanitizer(contentSanitizer);
        responseContentSanitizer(contentSanitizer);
        return this;
    }

    /**
     * Sets the {@link Function} to use to sanitize a response cause before logging. You can
     * sanitize the stack trace of the exception to remove sensitive information, or prevent from logging
     * the stack trace completely by returning {@code null} in the {@link Function}. If unset, will use
     * {@link Function#identity()}.
     */
    public LoggingDecoratorBuilder responseCauseSanitizer(
            Function<? super Throwable, ?> responseCauseSanitizer) {
        this.responseCauseSanitizer = requireNonNull(responseCauseSanitizer, "responseCauseSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response cause before logging.
     */
    protected Function<? super Throwable, ?> responseCauseSanitizer() {
        return responseCauseSanitizer;
    }

    @Override
    public String toString() {
        return toString(this, logger, requestLogLevel, successfulResponseLogLevel, failedResponseLogLevel,
                        requestLogLevelMapper, responseLogLevelMapper,
                        isRequestLogLevelMapperSet, isResponseLogLevelMapperSet,
                        requestHeadersSanitizer, requestContentSanitizer, requestTrailersSanitizer,
                        responseHeadersSanitizer, responseContentSanitizer, responseTrailersSanitizer);
    }

    private static String toString(
            LoggingDecoratorBuilder self,
            @Nullable Logger logger,
            LogLevel requestLogLevel,
            LogLevel successfulResponseLogLevel,
            LogLevel failureResponseLogLevel,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            boolean isRequestLogLevelMapperSet,
            boolean isResponseLogLevelMapperSet,
            Function<? super HttpHeaders, ?> requestHeadersSanitizer,
            Function<?, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ?> requestTrailersSanitizer,
            Function<? super HttpHeaders, ?> responseHeadersSanitizer,
            Function<Object, ?> responseContentSanitizer,
            Function<? super HttpHeaders, ?> responseTrailersSanitizer) {
        final ToStringHelper helper = MoreObjects.toStringHelper(self)
                                                 .omitNullValues()
                                                 .add("logger", logger);

        if (isRequestLogLevelMapperSet) {
            helper.add("requestLogLevelMapper", requestLogLevelMapper);
        } else {
            helper.add("requestLogLevel", requestLogLevel);
        }
        if (isResponseLogLevelMapperSet) {
            helper.add("responseLogLevelMapper", responseLogLevelMapper);
        } else {
            helper.add("successfulResponseLogLevel", successfulResponseLogLevel);
            helper.add("failureResponseLogLevel", failureResponseLogLevel);
        }

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
        return helper.toString();
    }
}
