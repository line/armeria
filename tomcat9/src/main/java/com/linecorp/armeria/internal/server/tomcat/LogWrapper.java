/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.tomcat;

import org.apache.juli.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an existing {@link Logger}.
 */
public final class LogWrapper implements Log {

    private final Logger delegate;

    public LogWrapper(Class<?> clazz) {
        delegate = LoggerFactory.getLogger(clazz);
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
        return delegate.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled() {
        return delegate.isErrorEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return delegate.isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }

    @Override
    public void trace(Object message) {
        delegate.trace("{}", message);
    }

    @Override
    public void trace(Object message, Throwable t) {
        delegate.trace("{}", message, t);
    }

    @Override
    public void debug(Object message) {
        delegate.debug("{}", message);
    }

    @Override
    public void debug(Object message, Throwable t) {
        delegate.debug("{}", message, t);
    }

    @Override
    public void info(Object message) {
        delegate.info("{}", message);
    }

    @Override
    public void info(Object message, Throwable t) {
        delegate.info("{}", message, t);
    }

    @Override
    public void warn(Object message) {
        delegate.warn("{}", message);
    }

    @Override
    public void warn(Object message, Throwable t) {
        delegate.warn("{}", message, t);
    }

    @Override
    public void error(Object message) {
        delegate.error("{}", message);
    }

    @Override
    public void error(Object message, Throwable t) {
        delegate.error("{}", message, t);
    }

    @Override
    public void fatal(Object message) {
        delegate.error("{}", message);
    }

    @Override
    public void fatal(Object message, Throwable t) {
        delegate.error("{}", message, t);
    }
}
