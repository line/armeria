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

package com.linecorp.armeria.server;

import org.slf4j.Logger;
import org.slf4j.Marker;

import com.linecorp.armeria.common.RequestContext;

@SuppressWarnings("MethodParameterNamingConvention")
final class RequestContextAwareLogger implements Logger {

    private final RequestContext ctx;
    private final Logger logger;

    RequestContextAwareLogger(RequestContext ctx, Logger logger) {
        this.ctx = ctx;
        this.logger = logger;
    }

    private String decorate(String msg) {
        final String prefix = ctx.toString();
        return new StringBuilder(prefix.length() + 1 + msg.length())
                .append(prefix)
                .append(' ')
                .append(msg).toString();
    }

    @Override
    public String getName() {
        return logger.getName();
    }

    @Override
    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return logger.isTraceEnabled(marker);
    }

    @Override
    public void trace(String msg) {
        if (isTraceEnabled()) {
            logger.trace(decorate(msg));
        }
    }

    @Override
    public void trace(String format, Object arg) {
        if (isTraceEnabled()) {
            logger.trace(decorate(format), arg);
        }
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        if (isTraceEnabled()) {
            logger.trace(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void trace(String format, Object... arguments) {
        if (isTraceEnabled()) {
            logger.trace(decorate(format), arguments);
        }
    }

    @Override
    public void trace(String msg, Throwable t) {
        if (isTraceEnabled()) {
            logger.trace(decorate(msg), t);
        }
    }

    @Override
    public void trace(Marker marker, String msg) {
        if (isTraceEnabled(marker)) {
            logger.trace(marker, decorate(msg));
        }
    }

    @Override
    public void trace(Marker marker, String format, Object arg) {
        if (isTraceEnabled(marker)) {
            logger.trace(marker, decorate(format), arg);
        }
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        if (isTraceEnabled(marker)) {
            logger.trace(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void trace(Marker marker, String format, Object... argArray) {
        if (isTraceEnabled(marker)) {
            logger.trace(marker, decorate(format), argArray);
        }
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        if (isTraceEnabled(marker)) {
            logger.trace(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return logger.isDebugEnabled(marker);
    }

    @Override
    public void debug(String msg) {
        if (isDebugEnabled()) {
            logger.debug(decorate(msg));
        }
    }

    @Override
    public void debug(String format, Object arg) {
        if (isDebugEnabled()) {
            logger.debug(decorate(format), arg);
        }
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        if (isDebugEnabled()) {
            logger.debug(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void debug(String format, Object... arguments) {
        if (isDebugEnabled()) {
            logger.debug(decorate(format), arguments);
        }
    }

    @Override
    public void debug(String msg, Throwable t) {
        if (isDebugEnabled()) {
            logger.debug(decorate(msg), t);
        }
    }

    @Override
    public void debug(Marker marker, String msg) {
        if (isDebugEnabled(marker)) {
            logger.debug(marker, decorate(msg));
        }
    }

    @Override
    public void debug(Marker marker, String format, Object arg) {
        if (isDebugEnabled(marker)) {
            logger.debug(marker, decorate(format), arg);
        }
    }

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2) {
        if (isDebugEnabled(marker)) {
            logger.debug(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void debug(Marker marker, String format, Object... arguments) {
        if (isDebugEnabled(marker)) {
            logger.debug(marker, decorate(format), arguments);
        }
    }

    @Override
    public void debug(Marker marker, String msg, Throwable t) {
        if (isDebugEnabled(marker)) {
            logger.debug(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return logger.isInfoEnabled(marker);
    }

    @Override
    public void info(String msg) {
        if (isInfoEnabled()) {
            logger.info(decorate(msg));
        }
    }

    @Override
    public void info(String format, Object arg) {
        if (isInfoEnabled()) {
            logger.info(decorate(format), arg);
        }
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        if (isInfoEnabled()) {
            logger.info(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void info(String format, Object... arguments) {
        if (isInfoEnabled()) {
            logger.info(decorate(format), arguments);
        }
    }

    @Override
    public void info(String msg, Throwable t) {
        if (isInfoEnabled()) {
            logger.info(decorate(msg), t);
        }
    }

    @Override
    public void info(Marker marker, String msg) {
        if (isInfoEnabled(marker)) {
            logger.info(marker, decorate(msg));
        }
    }

    @Override
    public void info(Marker marker, String format, Object arg) {
        if (isInfoEnabled(marker)) {
            logger.info(decorate(format), format, arg);
        }
    }

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {
        if (isInfoEnabled(marker)) {
            logger.info(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void info(Marker marker, String format, Object... arguments) {
        if (isInfoEnabled(marker)) {
            logger.info(marker, decorate(format), arguments);
        }
    }

    @Override
    public void info(Marker marker, String msg, Throwable t) {
        if (isInfoEnabled(marker)) {
            logger.info(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return logger.isWarnEnabled(marker);
    }

    @Override
    public void warn(String msg) {
        if (isWarnEnabled()) {
            logger.warn(decorate(msg));
        }
    }

    @Override
    public void warn(String format, Object arg) {
        if (isWarnEnabled()) {
            logger.warn(decorate(format), arg);
        }
    }

    @Override
    public void warn(String format, Object... arguments) {
        if (isWarnEnabled()) {
            logger.warn(decorate(format), arguments);
        }
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        if (isWarnEnabled()) {
            logger.warn(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void warn(String msg, Throwable t) {
        if (isWarnEnabled()) {
            logger.warn(decorate(msg), t);
        }
    }

    @Override
    public void warn(Marker marker, String msg) {
        if (isWarnEnabled(marker)) {
            logger.warn(marker, decorate(msg));
        }
    }

    @Override
    public void warn(Marker marker, String format, Object arg) {
        if (isWarnEnabled(marker)) {
            logger.warn(marker, decorate(format), arg);
        }
    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        if (isWarnEnabled(marker)) {
            logger.warn(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void warn(Marker marker, String format, Object... arguments) {
        if (isWarnEnabled(marker)) {
            logger.warn(marker, decorate(format), arguments);
        }
    }

    @Override
    public void warn(Marker marker, String msg, Throwable t) {
        if (isWarnEnabled(marker)) {
            logger.warn(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return logger.isErrorEnabled(marker);
    }

    @Override
    public void error(String msg) {
        if (isErrorEnabled()) {
            logger.error(decorate(msg));
        }
    }

    @Override
    public void error(String format, Object arg) {
        if (isErrorEnabled()) {
            logger.error(decorate(format), arg);
        }
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        if (isErrorEnabled()) {
            logger.error(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void error(String format, Object... arguments) {
        if (isErrorEnabled()) {
            logger.error(decorate(format), arguments);
        }
    }

    @Override
    public void error(String msg, Throwable t) {
        if (isErrorEnabled()) {
            logger.error(decorate(msg), t);
        }
    }

    @Override
    public void error(Marker marker, String msg) {
        if (isErrorEnabled(marker)) {
            logger.error(marker, decorate(msg));
        }
    }

    @Override
    public void error(Marker marker, String format, Object arg) {
        if (isErrorEnabled(marker)) {
            logger.error(marker, decorate(format), arg);
        }
    }

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {
        if (isErrorEnabled(marker)) {
            logger.error(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void error(Marker marker, String format, Object... arguments) {
        if (isErrorEnabled(marker)) {
            logger.error(marker, decorate(format), arguments);
        }
    }

    @Override
    public void error(Marker marker, String msg, Throwable t) {
        if (isErrorEnabled(marker)) {
            logger.error(marker, decorate(msg), t);
        }
    }

    @Override
    public String toString() {
        return "ServiceAwareLogger(" + logger + ')';
    }
}
