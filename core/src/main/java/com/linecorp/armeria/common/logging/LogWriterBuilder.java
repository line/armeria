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

import java.util.function.Predicate;

import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a new {@link LogWriter}.
 */
@UnstableApi
public final class LogWriterBuilder extends AbstractLogWriterBuilder {

    private Predicate<? super Throwable> responseCauseFilter = throwable -> false;

    LogWriterBuilder() {}

    @Override
    public LogWriterBuilder logger(Logger logger) {
        return (LogWriterBuilder) super.logger(logger);
    }

    @Override
    public LogWriterBuilder logger(String loggerName) {
        return (LogWriterBuilder) super.logger(loggerName);
    }

    @Override
    public LogWriterBuilder requestLogLevel(LogLevel requestLogLevel) {
        return (LogWriterBuilder) super.requestLogLevel(requestLogLevel);
    }

    @Override
    public LogWriterBuilder requestLogLevel(Class<? extends Throwable> clazz, LogLevel requestLogLevel) {
        return (LogWriterBuilder) super.requestLogLevel(clazz, requestLogLevel);
    }

    @Override
    public LogWriterBuilder requestLogLevelMapper(RequestLogLevelMapper requestLogLevelMapper) {
        return (LogWriterBuilder) super.requestLogLevelMapper(requestLogLevelMapper);
    }

    @Override
    public LogWriterBuilder responseLogLevel(HttpStatus status, LogLevel logLevel) {
        return (LogWriterBuilder) super.responseLogLevel(status, logLevel);
    }

    @Override
    public LogWriterBuilder responseLogLevel(HttpStatusClass statusClass, LogLevel logLevel) {
        return (LogWriterBuilder) super.responseLogLevel(statusClass, logLevel);
    }

    @Override
    public LogWriterBuilder responseLogLevel(Class<? extends Throwable> clazz, LogLevel logLevel) {
        return (LogWriterBuilder) super.responseLogLevel(clazz, logLevel);
    }

    @Override
    public LogWriterBuilder successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        return (LogWriterBuilder) super.successfulResponseLogLevel(successfulResponseLogLevel);
    }

    @Override
    public LogWriterBuilder failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        return (LogWriterBuilder) super.failureResponseLogLevel(failedResponseLogLevel);
    }

    @Override
    public LogWriterBuilder responseLogLevelMapper(ResponseLogLevelMapper responseLogLevelMapper) {
        return (LogWriterBuilder) super.responseLogLevelMapper(responseLogLevelMapper);
    }

    @Override
    public LogWriterBuilder logFormatter(LogFormatter logFormatter) {
        return (LogWriterBuilder) super.logFormatter(logFormatter);
    }

    /**
     * Sets the {@link Predicate} used for evaluating whether to log the response cause or not.
     * You can prevent logging the response cause by returning {@code true}
     * in the {@link Predicate}. By default, the response cause will always be logged.
     */
    public LogWriterBuilder responseCauseFilter(Predicate<? super Throwable> responseCauseFilter) {
        this.responseCauseFilter = requireNonNull(responseCauseFilter, "responseCauseFilter");
        return this;
    }

    /**
     * Returns a newly-created {@link LogWriter} based on the properties of this builder.
     */
    public LogWriter build() {
        Logger logger = logger();
        if (logger == null) {
            logger = DefaultLogWriter.defaultLogger;
        }
        LogFormatter logFormatter = logFormatter();
        if (logFormatter == null) {
            logFormatter = LogFormatter.ofText();
        }
        return new DefaultLogWriter(logger, requestLogLevelMapper(), responseLogLevelMapper(),
                                    responseCauseFilter, logFormatter);
    }
}
