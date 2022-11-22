/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.common.logback;

import java.util.HashMap;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.LogbackException;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.status.Status;

final class SocketAppenderWrapper implements Appender<ILoggingEvent> {

    private final Appender<ILoggingEvent> appender;

    SocketAppenderWrapper(Appender<ILoggingEvent> appender) {
        this.appender = appender;
    }

    @Override
    public String getName() {
        return appender.getName();
    }

    @Override
    public void doAppend(ILoggingEvent event) throws LogbackException {
        final HashMap<String, String> propsMap = new HashMap<>(event.getMDCPropertyMap());
        final ILoggingEvent wrappedEvent = new LoggingEventWrapper(event, propsMap);
        appender.doAppend(wrappedEvent);
    }

    @Override
    public void setName(String name) {
        appender.setName(name);
    }

    @Override
    public void setContext(Context context) {
        appender.setContext(context);
    }

    @Override
    public Context getContext() {
        return appender.getContext();
    }

    @Override
    public void addStatus(Status status) {
        appender.addStatus(status);
    }

    @Override
    public void addInfo(String msg) {
        appender.addInfo(msg);
    }

    @Override
    public void addInfo(String msg, Throwable ex) {
        appender.addInfo(msg, ex);
    }

    @Override
    public void addWarn(String msg) {
        appender.addWarn(msg);
    }

    @Override
    public void addWarn(String msg, Throwable ex) {
        appender.addWarn(msg, ex);
    }

    @Override
    public void addError(String msg) {
        appender.addError(msg);
    }

    @Override
    public void addError(String msg, Throwable ex) {
        appender.addError(msg, ex);
    }

    @Override
    public void addFilter(Filter<ILoggingEvent> newFilter) {
        appender.addFilter(newFilter);
    }

    @Override
    public void clearAllFilters() {
        appender.clearAllFilters();
    }

    @Override
    public List<Filter<ILoggingEvent>> getCopyOfAttachedFiltersList() {
        return appender.getCopyOfAttachedFiltersList();
    }

    @Override
    public FilterReply getFilterChainDecision(ILoggingEvent event) {
        return appender.getFilterChainDecision(event);
    }

    @Override
    public void start() {
        appender.start();
    }

    @Override
    public void stop() {
        appender.stop();
    }

    @Override
    public boolean isStarted() {
        return appender.isStarted();
    }
}
