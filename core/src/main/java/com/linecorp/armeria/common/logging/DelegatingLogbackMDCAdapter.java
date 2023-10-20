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

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.slf4j.spi.MDCAdapter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

final class DelegatingLogbackMDCAdapter extends LogbackMDCAdapter {

    static void maybeUpdateMdcAdapter(MDCAdapter adapter) {
        final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        final LoggerContext loggerContext = rootLogger.getLoggerContext();
        final Field mdcAdapter;
        try {
            mdcAdapter = LoggerContext.class.getDeclaredField("mdcAdapter");
            mdcAdapter.setAccessible(true);
            mdcAdapter.set(loggerContext, new DelegatingLogbackMDCAdapter(adapter));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Maybe Logback 1.4.7 or earlier. LoggerContext#mdcAdapter is added in Logback 1.4.8
            // https://github.com/qos-ch/logback/commit/ca7fbc7f4c1b1883092037ee4a662034586df07a#diff-1158785ec39613a90566e0290fa18a453340eb54352f678f5a25f2857289b4e4
        }
    }

    private final MDCAdapter delegate;

    private DelegatingLogbackMDCAdapter(MDCAdapter delegate) {
        this.delegate = delegate;
    }

    @Override
    public void put(String key, String val) {
        delegate.put(key, val);
    }

    @Override
    public String get(String key) {
        return delegate.get(key);
    }

    @Override
    public void remove(String key) {
        delegate.remove(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public Map<String, String> getPropertyMap() {
        return delegate.getCopyOfContextMap();
    }

    @Override
    public Map getCopyOfContextMap() {
        return delegate.getCopyOfContextMap();
    }

    @Override
    public void setContextMap(Map contextMap) {
        delegate.setContextMap(contextMap);
    }

    @Override
    public void pushByKey(String key, String value) {
        delegate.pushByKey(key, value);
    }

    @Override
    public String popByKey(String key) {
        return delegate.popByKey(key);
    }

    @Override
    public Deque<String> getCopyOfDequeByKey(String key) {
        return delegate.getCopyOfDequeByKey(key);
    }

    @Override
    public void clearDequeByKey(String key) {
        delegate.clearDequeByKey(key);
    }
}
