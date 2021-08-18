/*
 * Copyright 2016 LINE Corporation
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

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Log level.
 */
public enum LogLevel {
    /**
     * OFF log level.
     */
    OFF,
    /**
     * TRACE log level.
     */
    TRACE,
    /**
     * DEBUG log level.
     */
    DEBUG,
    /**
     * INFO log level.
     */
    INFO,
    /**
     * WARN log level.
     */
    WARN,
    /**
     * ERROR log level.
     */
    ERROR;

    /**
     * Returns {@code true} if this level is enabled.
     */
    public boolean isEnabled(Logger logger) {
        requireNonNull(logger, "logger");
        switch (this) {
            case OFF:
                return false;
            case TRACE:
                return logger.isTraceEnabled();
            case DEBUG:
                return logger.isDebugEnabled();
            case INFO:
                return logger.isInfoEnabled();
            case WARN:
                return logger.isWarnEnabled();
            case ERROR:
                return logger.isErrorEnabled();
            default:
                throw new Error();
        }
    }

    /**
     * Logs a message at this level.
     */
    public void log(Logger logger, String message) {
        requireNonNull(logger, "logger");
        requireNonNull(message, "message");
        switch (this) {
            case OFF:
                break;
            case TRACE:
                logger.trace(message);
                break;
            case DEBUG:
                logger.debug(message);
                break;
            case INFO:
                logger.info(message);
                break;
            case WARN:
                logger.warn(message);
                break;
            case ERROR:
                logger.error(message);
                break;
            default:
                throw new Error();
        }
    }

    /**
     * Logs a message at this level.
     */
    @SuppressWarnings("MethodParameterNamingConvention")
    public void log(Logger logger, String format, @Nullable Object arg1) {
        requireNonNull(logger, "logger");
        requireNonNull(format, "format");
        switch (this) {
            case OFF:
                break;
            case TRACE:
                logger.trace(format, arg1);
                break;
            case DEBUG:
                logger.debug(format, arg1);
                break;
            case INFO:
                logger.info(format, arg1);
                break;
            case WARN:
                logger.warn(format, arg1);
                break;
            case ERROR:
                logger.error(format, arg1);
                break;
            default:
                throw new Error();
        }
    }

    /**
     * Logs a message at this level.
     */
    @SuppressWarnings("MethodParameterNamingConvention")
    public void log(Logger logger, String format, @Nullable Object arg1, @Nullable Object arg2) {
        requireNonNull(logger, "logger");
        requireNonNull(format, "format");
        switch (this) {
            case OFF:
                break;
            case TRACE:
                logger.trace(format, arg1, arg2);
                break;
            case DEBUG:
                logger.debug(format, arg1, arg2);
                break;
            case INFO:
                logger.info(format, arg1, arg2);
                break;
            case WARN:
                logger.warn(format, arg1, arg2);
                break;
            case ERROR:
                logger.error(format, arg1, arg2);
                break;
            default:
                throw new Error();
        }
    }

    /**
     * Logs a message at this level.
     */
    @SuppressWarnings("MethodParameterNamingConvention")
    public void log(Logger logger, String format,
                    @Nullable Object arg1, @Nullable Object arg2, @Nullable Object arg3) {
        requireNonNull(logger, "logger");
        requireNonNull(format, "format");
        switch (this) {
            case OFF:
                break;
            case TRACE:
                logger.trace(format, arg1, arg2, arg3);
                break;
            case DEBUG:
                logger.debug(format, arg1, arg2, arg3);
                break;
            case INFO:
                logger.info(format, arg1, arg2, arg3);
                break;
            case WARN:
                logger.warn(format, arg1, arg2, arg3);
                break;
            case ERROR:
                logger.error(format, arg1, arg2, arg3);
                break;
            default:
                throw new Error();
        }
    }

    /**
     * Logs a message at this level.
     */
    public void log(Logger logger, String format, Object... args) {
        requireNonNull(logger, "logger");
        requireNonNull(format, "format");
        switch (this) {
            case OFF:
                break;
            case TRACE:
                logger.trace(format, args);
                break;
            case DEBUG:
                logger.debug(format, args);
                break;
            case INFO:
                logger.info(format, args);
                break;
            case WARN:
                logger.warn(format, args);
                break;
            case ERROR:
                logger.error(format, args);
                break;
            default:
                throw new Error();
        }
    }
}
