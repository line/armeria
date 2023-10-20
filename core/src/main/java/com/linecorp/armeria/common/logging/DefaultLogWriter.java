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

import static com.linecorp.armeria.common.logging.LogWriterBuilder.DEFAULT_REQUEST_LOG_LEVEL;
import static com.linecorp.armeria.common.logging.LogWriterBuilder.DEFAULT_REQUEST_LOG_LEVEL_MAPPER;
import static com.linecorp.armeria.common.logging.LogWriterBuilder.DEFAULT_RESPONSE_LOG_LEVEL_MAPPER;
import static java.util.Objects.requireNonNull;

import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.SafeCloseable;

final class DefaultLogWriter implements LogWriter {

    static final Logger defaultLogger = LoggerFactory.getLogger(LogWriter.class);

    static final DefaultLogWriter DEFAULT =
            new DefaultLogWriter(defaultLogger, DEFAULT_REQUEST_LOG_LEVEL_MAPPER,
                                 DEFAULT_RESPONSE_LOG_LEVEL_MAPPER,
                                 throwable -> false, LogFormatter.ofText());

    private static boolean warnedNullRequestLogLevelMapper;
    private static boolean warnedNullResponseLogLevelMapper;

    private final Logger logger;
    private final RequestLogLevelMapper requestLogLevelMapper;
    private final ResponseLogLevelMapper responseLogLevelMapper;
    private final Predicate<? super Throwable> responseCauseFilter;
    private final LogFormatter logFormatter;

    DefaultLogWriter(Logger logger, RequestLogLevelMapper requestLogLevelMapper,
                     ResponseLogLevelMapper responseLogLevelMapper,
                     Predicate<? super Throwable> responseCauseFilter, LogFormatter logFormatter) {
        this.logger = logger;
        this.requestLogLevelMapper = requestLogLevelMapper;
        this.responseLogLevelMapper = responseLogLevelMapper;
        this.responseCauseFilter = responseCauseFilter;
        this.logFormatter = logFormatter;
    }

    @Override
    public void logRequest(RequestOnlyLog log) {
        requireNonNull(log, "log");
        LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
        if (requestLogLevel == null) {
            if (!warnedNullRequestLogLevelMapper) {
                warnedNullRequestLogLevelMapper = true;
                logger.warn("requestLogLevelMapper.apply() returned null; using default log level");
            }
            requestLogLevel = DEFAULT_REQUEST_LOG_LEVEL;
        }
        if (requestLogLevel.isEnabled(logger)) {
            try (SafeCloseable ignored = log.context().push()) {
                // We don't log requestCause when it's not null because responseCause is the same exception when
                // the requestCause is not null. That's way we don't have requestCauseSanitizer.
                requestLogLevel.log(logger, logFormatter.formatRequest(log));
            }
        }
    }

    @Override
    public void logResponse(RequestLog log) {
        requireNonNull(log, "log");
        LogLevel responseLogLevel = responseLogLevelMapper.apply(log);
        if (responseLogLevel == null) {
            if (!warnedNullResponseLogLevelMapper) {
                warnedNullResponseLogLevelMapper = true;
                logger.warn("responseLogLevelMapper.apply() returned null; using default log level mapper");
            }
            responseLogLevel = DEFAULT_RESPONSE_LOG_LEVEL_MAPPER.apply(log);
            assert responseLogLevel != null;
        }
        final Throwable responseCause = log.responseCause();

        if (responseLogLevel.isEnabled(logger)) {
            final String responseStr = logFormatter.formatResponse(log);
            try (SafeCloseable ignored = log.context().push()) {
                if (responseCause == null) {
                    responseLogLevel.log(logger, responseStr);
                    return;
                }

                final LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
                assert requestLogLevel != null;
                if (!requestLogLevel.isEnabled(logger)) {
                    // Request wasn't logged, but this is an unsuccessful response,
                    // so we log the request too to help debugging.
                    responseLogLevel.log(logger, logFormatter.formatRequest(log));
                }

                if (responseCauseFilter.test(responseCause)) {
                    responseLogLevel.log(logger, responseStr);
                } else {
                    responseLogLevel.log(logger, responseStr, responseCause);
                }
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("logger", logger)
                          .add("requestLogLevelMapper", requestLogLevelMapper)
                          .add("responseLogLevelMapper", responseLogLevelMapper)
                          .add("responseCauseFilter", responseCauseFilter)
                          .add("logFormatter", logFormatter)
                          .toString();
    }
}
