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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation for {@link LogWriter}.
 */
@UnstableApi
public abstract class AbstractLogWriterBuilder {

    public static final LogLevel DEFAULT_REQUEST_LOG_LEVEL = LogLevel.DEBUG;

    public static final RequestLogLevelMapper DEFAULT_REQUEST_LOG_LEVEL_MAPPER =
            RequestLogLevelMapper.of(DEFAULT_REQUEST_LOG_LEVEL);

    public static final ResponseLogLevelMapper DEFAULT_RESPONSE_LOG_LEVEL_MAPPER =
            ResponseLogLevelMapper.of(LogLevel.DEBUG, LogLevel.WARN);

    @Nullable
    protected Logger logger;
    @Nullable
    protected RequestLogLevelMapper requestLogLevelMapper;
    @Nullable
    protected ResponseLogLevelMapper responseLogLevelMapper;
    @Nullable
    protected LogFormatter logFormatter;

    /**
     * Sets the {@link Logger} to use when logging.
     * If unset, a default {@link Logger} will be used.
     */
    public AbstractLogWriterBuilder logger(Logger logger) {
        this.logger = requireNonNull(logger, "logger");
        return this;
    }

    @Nullable
    protected Logger logger() {
        return logger;
    }

    /**
     * Sets the name of the {@link Logger} to use when logging.
     * This method is a shortcut for {@code this.logger(LoggerFactory.getLogger(loggerName))}.
     */
    public AbstractLogWriterBuilder logger(String loggerName) {
        requireNonNull(loggerName, "loggerName");
        logger = LoggerFactory.getLogger(loggerName);
        return this;
    }

    /**
     * Sets the {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#DEBUG}.
     */
    public AbstractLogWriterBuilder requestLogLevel(LogLevel requestLogLevel) {
        requireNonNull(requestLogLevel, "requestLogLevel");
        return requestLogLevelMapper(RequestLogLevelMapper.of(requestLogLevel));
    }

    /**
     * Sets the {@link LogLevel} to use when the response fails with the specified {@link Throwable}.
     */
    public AbstractLogWriterBuilder requestLogLevel(Class<? extends Throwable> clazz, LogLevel requestLogLevel) {
        requireNonNull(clazz, "clazz");
        requireNonNull(requestLogLevel, "requestLogLevel");
        return requestLogLevelMapper(RequestLogLevelMapper.of(clazz, requestLogLevel));
    }

    /**
     * Sets the {@link RequestLogLevelMapper} to use when mapping the log level of request logs.
     */
    public AbstractLogWriterBuilder requestLogLevelMapper(RequestLogLevelMapper requestLogLevelMapper) {
        requireNonNull(requestLogLevelMapper, "requestLogLevelMapper");
        if (this.requestLogLevelMapper == null) {
            this.requestLogLevelMapper = requestLogLevelMapper;
        } else {
            this.requestLogLevelMapper = this.requestLogLevelMapper.orElse(requestLogLevelMapper);
        }
        return this;
    }

    protected RequestLogLevelMapper requestLogLevelMapper() {
        if (requestLogLevelMapper == null) {
            return DEFAULT_REQUEST_LOG_LEVEL_MAPPER;
        }
        return requestLogLevelMapper.orElse(DEFAULT_REQUEST_LOG_LEVEL_MAPPER);
    }

    /**
     * Sets the {@link LogLevel} to use when logging responses whose status is equal to the specified
     * {@link HttpStatus}.
     */
    public AbstractLogWriterBuilder responseLogLevel(HttpStatus status, LogLevel logLevel) {
        return responseLogLevelMapper(ResponseLogLevelMapper.of(status, logLevel));
    }

    /**
     * Sets the {@link LogLevel} to use when logging responses whose status belongs to the specified
     * {@link HttpStatusClass}.
     */
    public AbstractLogWriterBuilder responseLogLevel(HttpStatusClass statusClass, LogLevel logLevel) {
        return responseLogLevelMapper(ResponseLogLevelMapper.of(statusClass, logLevel));
    }

    /**
     * Sets the {@link LogLevel} to use when the response fails with the specified {@link Throwable}.
     */
    public AbstractLogWriterBuilder responseLogLevel(Class<? extends Throwable> clazz, LogLevel logLevel) {
        requireNonNull(clazz, "clazz");
        requireNonNull(logLevel, "logLevel");
        return responseLogLevelMapper(ResponseLogLevelMapper.of(clazz, logLevel));
    }

    /**
     * Sets the {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * {@link LogLevel#DEBUG} will be used by default.
     */
    public AbstractLogWriterBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        requireNonNull(successfulResponseLogLevel, "successfulResponseLogLevel");
        return responseLogLevelMapper(log -> log.responseCause() == null ? successfulResponseLogLevel : null);
    }

    /**
     * Sets the {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * {@link LogLevel#WARN} will be used by default.
     */
    public AbstractLogWriterBuilder failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        requireNonNull(failedResponseLogLevel, "failedResponseLogLevel");
        return responseLogLevelMapper(log -> log.responseCause() != null ? failedResponseLogLevel : null);
    }

    /**
     * Sets the {@link ResponseLogLevelMapper} to use when mapping the log level of response logs.
     */
    public AbstractLogWriterBuilder responseLogLevelMapper(ResponseLogLevelMapper responseLogLevelMapper) {
        requireNonNull(responseLogLevelMapper, "responseLogLevelMapper");
        if (this.responseLogLevelMapper == null) {
            this.responseLogLevelMapper = responseLogLevelMapper;
        } else {
            this.responseLogLevelMapper = this.responseLogLevelMapper.orElse(responseLogLevelMapper);
        }
        return this;
    }

    protected ResponseLogLevelMapper responseLogLevelMapper() {
        if (responseLogLevelMapper == null) {
            return DEFAULT_RESPONSE_LOG_LEVEL_MAPPER;
        }
        return responseLogLevelMapper.orElse(DEFAULT_RESPONSE_LOG_LEVEL_MAPPER);
    }

    /**
     * Sets the {@link LogFormatter} which converts a {@link RequestOnlyLog} or {@link RequestLog}
     * into a log message. By default {@link LogFormatter#ofText()} will be used.
     */
    public AbstractLogWriterBuilder logFormatter(LogFormatter logFormatter) {
        this.logFormatter = requireNonNull(logFormatter, "logFormatter");
        return this;
    }

    @Nullable
    protected LogFormatter logFormatter() {
        return logFormatter;
    }
}
