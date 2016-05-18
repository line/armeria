/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
    private final Logger l;

    RequestContextAwareLogger(RequestContext ctx, Logger l) {
        this.ctx = ctx;
        this.l = l;
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
        return l.getName();
    }

    @Override
    public boolean isTraceEnabled() {
        return l.isTraceEnabled();
    }

    @Override
    public void trace(String msg) {
        if (isTraceEnabled()) {
            l.trace(decorate(msg));
        }
    }

    @Override
    public void trace(String format, Object arg) {
        if (isTraceEnabled()) {
            l.trace(decorate(format), arg);
        }
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        if (isTraceEnabled()) {
            l.trace(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void trace(String format, Object... arguments) {
        if (isTraceEnabled()) {
            l.trace(decorate(format), arguments);
        }
    }

    @Override
    public void trace(String msg, Throwable t) {
        if (isTraceEnabled()) {
            l.trace(decorate(msg), t);
        }
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return l.isTraceEnabled(marker);
    }

    @Override
    public void trace(Marker marker, String msg) {
        if (isTraceEnabled(marker)) {
            l.trace(marker, decorate(msg));
        }
    }

    @Override
    public void trace(Marker marker, String format, Object arg) {
        if (isTraceEnabled(marker)) {
            l.trace(marker, decorate(format), arg);
        }
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        if (isTraceEnabled(marker)) {
            l.trace(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void trace(Marker marker, String format, Object... argArray) {
        if (isTraceEnabled(marker)) {
            l.trace(marker, decorate(format), argArray);
        }
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        if (isTraceEnabled(marker)) {
            l.trace(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isDebugEnabled() {
        return l.isDebugEnabled();
    }

    @Override
    public void debug(String msg) {
        if (isDebugEnabled()) {
            l.debug(decorate(msg));
        }
    }

    @Override
    public void debug(String format, Object arg) {
        if (isDebugEnabled()) {
            l.debug(decorate(format), arg);
        }
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        if (isDebugEnabled()) {
            l.debug(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void debug(String format, Object... arguments) {
        if (isDebugEnabled()) {
            l.debug(decorate(format), arguments);
        }
    }

    @Override
    public void debug(String msg, Throwable t) {
        if (isDebugEnabled()) {
            l.debug(decorate(msg), t);
        }
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return l.isDebugEnabled(marker);
    }

    @Override
    public void debug(Marker marker, String msg) {
        if (isDebugEnabled(marker)) {
            l.debug(marker, decorate(msg));
        }
    }

    @Override
    public void debug(Marker marker, String format, Object arg) {
        if (isDebugEnabled(marker)) {
            l.debug(marker, decorate(format), arg);
        }
    }

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2) {
        if (isDebugEnabled(marker)) {
            l.debug(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void debug(Marker marker, String format, Object... arguments) {
        if (isDebugEnabled(marker)) {
            l.debug(marker, decorate(format), arguments);
        }
    }

    @Override
    public void debug(Marker marker, String msg, Throwable t) {
        if (isDebugEnabled(marker)) {
            l.debug(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isInfoEnabled() {
        return l.isInfoEnabled();
    }

    @Override
    public void info(String msg) {
        if (isInfoEnabled()) {
            l.info(decorate(msg));
        }
    }

    @Override
    public void info(String format, Object arg) {
        if (isInfoEnabled()) {
            l.info(decorate(format), arg);
        }
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        if (isInfoEnabled()) {
            l.info(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void info(String format, Object... arguments) {
        if (isInfoEnabled()) {
            l.info(decorate(format), arguments);
        }
    }

    @Override
    public void info(String msg, Throwable t) {
        if (isInfoEnabled()) {
            l.info(decorate(msg), t);
        }
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return l.isInfoEnabled(marker);
    }

    @Override
    public void info(Marker marker, String msg) {
        if (isInfoEnabled(marker)) {
            l.info(marker, decorate(msg));
        }
    }

    @Override
    public void info(Marker marker, String format, Object arg) {
        if (isInfoEnabled(marker)) {
            l.info(decorate(format), format, arg);
        }
    }

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {
        if (isInfoEnabled(marker)) {
            l.info(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void info(Marker marker, String format, Object... arguments) {
        if (isInfoEnabled(marker)) {
            l.info(marker, decorate(format), arguments);
        }
    }

    @Override
    public void info(Marker marker, String msg, Throwable t) {
        if (isInfoEnabled(marker)) {
            l.info(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isWarnEnabled() {
        return l.isWarnEnabled();
    }

    @Override
    public void warn(String msg) {
        if (isWarnEnabled()) {
            l.warn(decorate(msg));
        }
    }

    @Override
    public void warn(String format, Object arg) {
        if (isWarnEnabled()) {
            l.warn(decorate(format), arg);
        }
    }

    @Override
    public void warn(String format, Object... arguments) {
        if (isWarnEnabled()) {
            l.warn(decorate(format), arguments);
        }
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        if (isWarnEnabled()) {
            l.warn(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void warn(String msg, Throwable t) {
        if (isWarnEnabled()) {
            l.warn(decorate(msg), t);
        }
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return l.isWarnEnabled(marker);
    }

    @Override
    public void warn(Marker marker, String msg) {
        if (isWarnEnabled(marker)) {
            l.warn(marker, decorate(msg));
        }
    }

    @Override
    public void warn(Marker marker, String format, Object arg) {
        if (isWarnEnabled(marker)) {
            l.warn(marker, decorate(format), arg);
        }
    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        if (isWarnEnabled(marker)) {
            l.warn(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void warn(Marker marker, String format, Object... arguments) {
        if (isWarnEnabled(marker)) {
            l.warn(marker, decorate(format), arguments);
        }
    }

    @Override
    public void warn(Marker marker, String msg, Throwable t) {
        if (isWarnEnabled(marker)) {
            l.warn(marker, decorate(msg), t);
        }
    }

    @Override
    public boolean isErrorEnabled() {
        return l.isErrorEnabled();
    }

    @Override
    public void error(String msg) {
        if (isErrorEnabled()) {
            l.error(decorate(msg));
        }
    }

    @Override
    public void error(String format, Object arg) {
        if (isErrorEnabled()) {
            l.error(decorate(format), arg);
        }
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        if (isErrorEnabled()) {
            l.error(decorate(format), arg1, arg2);
        }
    }

    @Override
    public void error(String format, Object... arguments) {
        if (isErrorEnabled()) {
            l.error(decorate(format), arguments);
        }
    }

    @Override
    public void error(String msg, Throwable t) {
        if (isErrorEnabled()) {
            l.error(decorate(msg), t);
        }
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return l.isErrorEnabled(marker);
    }

    @Override
    public void error(Marker marker, String msg) {
        if (isErrorEnabled(marker)) {
            l.error(marker, decorate(msg));
        }
    }

    @Override
    public void error(Marker marker, String format, Object arg) {
        if (isErrorEnabled(marker)) {
            l.error(marker, decorate(format), arg);
        }
    }

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {
        if (isErrorEnabled(marker)) {
            l.error(marker, decorate(format), arg1, arg2);
        }
    }

    @Override
    public void error(Marker marker, String format, Object... arguments) {
        if (isErrorEnabled(marker)) {
            l.error(marker, decorate(format), arguments);
        }
    }

    @Override
    public void error(Marker marker, String msg, Throwable t) {
        if (isErrorEnabled(marker)) {
            l.error(marker, decorate(msg), t);
        }
    }

    @Override
    public String toString() {
        return "ServiceAwareLogger(" + l + ')';
    }
}
