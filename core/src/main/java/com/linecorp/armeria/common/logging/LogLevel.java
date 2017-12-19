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

import org.slf4j.Logger;

/**
 * Log level.
 */
public enum LogLevel {
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
        switch (this) {
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
        switch (this) {
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
    public void log(Logger logger, String format, Object arg1) {
        switch (this) {
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
    public void log(Logger logger, String format, Object arg1, Object arg2) {
        switch (this) {
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
}
