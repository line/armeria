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
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a new logging decorator.
 */
public abstract class LoggingDecoratorBuilder {

    private static <T, U> BiFunction<T, U, @Nullable String> convertToStringSanitizer(
            BiFunction<T, U, ? extends @Nullable Object> originalSanitizer) {
        return (first, second) -> {
            final Object sanitized = originalSanitizer.apply(first, second);
            return sanitized != null ? sanitized.toString() : null;
        };
    }

    @Nullable
    private LogWriter logWriter;
    @Nullable
    private Logger defaultLogger;
    /**
     * {@link #logWriterBuilder} and {@link #logFormatterBuilder} are not null if true.
     */
    private boolean shouldBuildLogWriter;
    @Nullable
    private LogWriterBuilder logWriterBuilder;
    @Nullable
    private TextLogFormatterBuilder logFormatterBuilder;

    /**
     * Sets the logger that is used when neither {@link #logWriter(LogWriter)} nor {@link #logger(Logger)}
     * is set.
     */
    protected LoggingDecoratorBuilder defaultLogger(Logger defaultLogger) {
        requireNonNull(defaultLogger, "defaultLogger");
        this.defaultLogger = defaultLogger;
        return this;
    }

    /**
     * Sets the {@link Logger} to use when logging.
     * If unset, a default {@link Logger} will be used.
     *
     * @deprecated Use {@link LogWriterBuilder#logger(Logger)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder logger(Logger logger) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.logger(logger);
        return this;
    }

    /**
     * Sets the name of the {@link Logger} to use when logging.
     * This method is a shortcut for {@code this.logger(LoggerFactory.getLogger(loggerName))}.
     *
     * @deprecated Use {@link LogWriterBuilder#logger(String)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder logger(String loggerName) {
        requireNonNull(loggerName, "loggerName");
        maybeCreateLogWriterBuilder();
        logWriterBuilder.logger(LoggerFactory.getLogger(loggerName));
        return this;
    }

    /**
     * Returns the {@link Logger} the user specified to use,
     * or {@code null} if not set and a default logger should be used.
     */
    @Nullable
    protected final Logger logger() {
        if (logWriterBuilder != null) {
            return logWriterBuilder.logger();
        }
        return null;
    }

    /**
     * Sets the {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#DEBUG}.
     *
     * @deprecated Use {@link LogWriterBuilder#requestLogLevel(LogLevel)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder requestLogLevel(LogLevel requestLogLevel) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.requestLogLevel(requestLogLevel);
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when the response fails with the specified {@link Throwable}.
     *
     * @deprecated Use {@link LogWriterBuilder#requestLogLevel(Class, LogLevel)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder requestLogLevel(Class<? extends Throwable> clazz, LogLevel requestLogLevel) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.requestLogLevel(clazz, requestLogLevel);
        return this;
    }

    /**
     * Sets the {@link Function} to use when mapping the log level of request logs.
     *
     * @deprecated Use {@link #requestLogLevelMapper(RequestLogLevelMapper)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder requestLogLevelMapper(
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper) {
        requireNonNull(requestLogLevelMapper, "requestLogLevelMapper");
        maybeCreateLogWriterBuilder();
        logWriterBuilder.requestLogLevelMapper(requestLogLevelMapper::apply);
        return this;
    }

    /**
     * Sets the {@link RequestLogLevelMapper} to use when mapping the log level of request logs.
     *
     * @deprecated Use {@link LogWriterBuilder#requestLogLevelMapper(RequestLogLevelMapper)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder requestLogLevelMapper(RequestLogLevelMapper requestLogLevelMapper) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.requestLogLevelMapper(requestLogLevelMapper);
        return this;
    }

    /**
     * Returns the {@link RequestLogLevelMapper} to use when logging request logs.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Deprecated
    @Nullable
    protected final RequestLogLevelMapper requestLogLevelMapper() {
        if (logWriterBuilder != null) {
            return logWriterBuilder.requestLogLevelMapper();
        }
        return null;
    }

    /**
     * Sets the {@link LogLevel} to use when logging responses whose status is equal to the specified
     * {@link HttpStatus}.
     *
     * @deprecated Use {@link LogWriterBuilder#responseLogLevel(HttpStatus, LogLevel)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseLogLevel(HttpStatus status, LogLevel logLevel) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.responseLogLevel(status, logLevel);
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when logging responses whose status belongs to the specified
     * {@link HttpStatusClass}.
     *
     * @deprecated Use {@link LogWriterBuilder#responseLogLevel(HttpStatusClass, LogLevel)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseLogLevel(HttpStatusClass statusClass, LogLevel logLevel) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.responseLogLevel(statusClass, logLevel);
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when the response fails with the specified {@link Throwable}.
     *
     * @deprecated Use {@link LogWriterBuilder#responseLogLevel(Class, LogLevel)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseLogLevel(Class<? extends Throwable> clazz, LogLevel logLevel) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.responseLogLevel(clazz, logLevel);
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * {@link LogLevel#DEBUG} will be used by default.
     *
     * @deprecated Use {@link LogWriterBuilder#successfulResponseLogLevel(LogLevel)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.successfulResponseLogLevel(successfulResponseLogLevel);
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * {@link LogLevel#WARN} will be used by default.
     *
     * @deprecated Use {@link LogWriterBuilder#failureResponseLogLevel(LogLevel)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.failureResponseLogLevel(failedResponseLogLevel);
        return this;
    }

    /**
     * Sets the {@link Function} to use when mapping the log level of response logs.
     *
     * @deprecated Use {@link #responseLogLevelMapper(ResponseLogLevelMapper)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseLogLevelMapper(
            Function<? super RequestLog, LogLevel> responseLogLevelMapper) {
        requireNonNull(responseLogLevelMapper, "responseLogLevelMapper");
        maybeCreateLogWriterBuilder();
        return responseLogLevelMapper(responseLogLevelMapper::apply);
    }

    /**
     * Sets the {@link ResponseLogLevelMapper} to use when mapping the log level of response logs.
     *
     * @deprecated Use {@link LogWriterBuilder#responseLogLevelMapper(ResponseLogLevelMapper)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseLogLevelMapper(ResponseLogLevelMapper responseLogLevelMapper) {
        maybeCreateLogWriterBuilder();
        logWriterBuilder.responseLogLevelMapper(responseLogLevelMapper);
        return this;
    }

    /**
     * Returns the {@link ResponseLogLevelMapper} to use when logging response logs.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Deprecated
    @Nullable
    protected final ResponseLogLevelMapper responseLogLevelMapper() {
        if (logWriterBuilder != null) {
            return logWriterBuilder.responseLogLevelMapper();
        }
        return null;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will
     * not sanitize request headers.
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
    public LoggingDecoratorBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestHeadersSanitizer) {
        requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        maybeCreateLogWriterBuilder();
        logFormatterBuilder.requestHeadersSanitizer(convertToStringSanitizer(requestHeadersSanitizer));
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request headers before logging.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Deprecated
    @Nullable
    protected final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
    requestHeadersSanitizer() {
        if (logFormatterBuilder != null) {
            return logFormatterBuilder.requestHeadersSanitizer();
        }
        return null;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will not sanitize response headers.
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseHeadersSanitizer) {
        maybeCreateLogWriterBuilder();
        requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        logFormatterBuilder.responseHeadersSanitizer(convertToStringSanitizer(responseHeadersSanitizer));
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response headers before logging.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Nullable
    @Deprecated
    protected final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
    responseHeadersSanitizer() {
        if (logFormatterBuilder != null) {
            return logFormatterBuilder.responseHeadersSanitizer();
        }
        return null;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request trailers before logging. If unset,
     * will not sanitize request trailers.
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
    public LoggingDecoratorBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> requestTrailersSanitizer) {
        requireNonNull(requestTrailersSanitizer, "requestTrailersSanitizer");
        maybeCreateLogWriterBuilder();
        logFormatterBuilder.requestTrailersSanitizer(convertToStringSanitizer(requestTrailersSanitizer));
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request trailers before logging.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Deprecated
    @Nullable
    protected final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable Object> requestTrailersSanitizer() {
        if (logFormatterBuilder != null) {
            return logFormatterBuilder.requestTrailersSanitizer();
        }
        return null;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response trailers before logging. If unset,
     * will not sanitize response trailers.
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> responseTrailersSanitizer) {
        requireNonNull(responseTrailersSanitizer, "responseTrailersSanitizer");
        maybeCreateLogWriterBuilder();
        logFormatterBuilder.responseTrailersSanitizer(convertToStringSanitizer(responseTrailersSanitizer));
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response trailers before logging.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Nullable
    @Deprecated
    protected final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends @Nullable Object>
    responseTrailersSanitizer() {
        if (logFormatterBuilder != null) {
            return logFormatterBuilder.responseTrailersSanitizer();
        }
        return null;
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
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @see #requestHeadersSanitizer(BiFunction)
     * @see #requestTrailersSanitizer(BiFunction)
     * @see #responseHeadersSanitizer(BiFunction)
     * @see #responseTrailersSanitizer(BiFunction)
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
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
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
    public LoggingDecoratorBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> requestContentSanitizer) {
        requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        maybeCreateLogWriterBuilder();
        logFormatterBuilder.requestContentSanitizer(convertToStringSanitizer(requestContentSanitizer));
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request content before logging.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Nullable
    @Deprecated
    protected final BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
    requestContentSanitizer() {
        if (logFormatterBuilder != null) {
            return logFormatterBuilder.requestContentSanitizer();
        }
        return null;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset,
     * will not sanitize response content.
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> responseContentSanitizer) {
        requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        maybeCreateLogWriterBuilder();
        logFormatterBuilder.responseContentSanitizer(convertToStringSanitizer(responseContentSanitizer));
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response content before logging.
     *
     * @deprecated Deprecated for removal in the next major version.
     */
    @Nullable
    @Deprecated
    protected final BiFunction<? super RequestContext, Object, ? extends @Nullable Object>
    responseContentSanitizer() {
        if (logFormatterBuilder != null) {
            return logFormatterBuilder.responseContentSanitizer();
        }
        return null;
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
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @see #requestContentSanitizer(BiFunction)
     * @see #responseContentSanitizer(BiFunction)
     * @deprecated Use {@link LogFormatter} to set the sanitizer.
     */
    @Deprecated
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
     *
     * <p>Note that this method is no longer supported. You must use
     * {@link LogWriterBuilder#responseCauseFilter(BiPredicate)}.
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogFormatter} are specified.
     * @deprecated Use {@link LogWriterBuilder#responseCauseFilter(BiPredicate)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseCauseSanitizer(
            BiFunction<? super RequestContext, ? super Throwable,
                    ? extends @Nullable Object> responseCauseSanitizer) {
        requireNonNull(responseCauseSanitizer, "responseCauseSanitizer");
        maybeCreateLogWriterBuilder();
        logWriterBuilder.responseCauseFilter((ctx, cause) -> responseCauseSanitizer.apply(ctx, cause) != null);
        return this;
    }

    /**
     * Don't use this method. null is always returned.
     * @deprecated Deprecated for removal in the next major version.
     */
    @Nullable
    @Deprecated
    protected final BiFunction<? super RequestContext, ? super Throwable, ? extends @Nullable Object>
    responseCauseSanitizer() {
        return null;
    }

    /**
     * Sets the {@link Predicate} used for evaluating whether to log the response cause or not.
     * You can prevent logging the response cause by returning {@code true}
     * in the {@link Predicate}. By default, the response cause will always be logged.
     *
     * @deprecated Use {@link LogWriterBuilder#responseCauseFilter(BiPredicate)} instead.
     */
    @Deprecated
    public LoggingDecoratorBuilder responseCauseFilter(Predicate<Throwable> responseCauseFilter) {
        requireNonNull(responseCauseFilter, "responseCauseFilter");
        maybeCreateLogWriterBuilder();
        logWriterBuilder.responseCauseFilter((ctx, cause) -> responseCauseFilter.test(cause));
        return this;
    }

    /**
     * Sets the {@link LogWriter} which write a {@link RequestOnlyLog} or {@link RequestLog}.
     * By default {@link LogWriter#of()} will be used.
     *
     * @throws IllegalStateException If both the log sanitizers and the {@link LogWriter} are specified.
     */
    @UnstableApi
    public LoggingDecoratorBuilder logWriter(LogWriter logWriter) {
        if (shouldBuildLogWriter) {
            throw new IllegalStateException(
                    "The logWriter and the log properties cannot be set together.");
        }
        this.logWriter = requireNonNull(logWriter, "logWriter");
        return this;
    }

    /**
     * Returns {@link LogWriter} if set.
     */
    protected final LogWriter logWriter() {
        if (logWriter != null) {
            return logWriter;
        }

        if (!shouldBuildLogWriter) {
            // Neither logWriter nor log properties are set.
            if (defaultLogger != null) {
                return LogWriter.of(defaultLogger);
            } else {
                return LogWriter.of();
            }
        }

        assert logWriterBuilder != null;
        assert logFormatterBuilder != null;
        final LogFormatter logFormatter = logFormatterBuilder.build();
        logWriterBuilder.logFormatter(logFormatter);
        if (logWriterBuilder.logger() == null && defaultLogger != null) {
            logWriterBuilder.logger(defaultLogger);
        }
        return logWriterBuilder.build();
    }

    private void maybeCreateLogWriterBuilder() {
        if (logWriter != null) {
            throw new IllegalStateException("The logWriter and the log properties cannot be set together.");
        }
        if (shouldBuildLogWriter) {
            return;
        }
        shouldBuildLogWriter = true;
        logWriterBuilder = LogWriter.builder();
        logFormatterBuilder = LogFormatter.builderForText();
    }
}
