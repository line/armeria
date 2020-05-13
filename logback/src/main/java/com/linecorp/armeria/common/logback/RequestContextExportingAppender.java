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
package com.linecorp.armeria.common.logback;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.MDC;
import org.slf4j.Marker;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.BuiltInProperty;
import com.linecorp.armeria.common.logging.RequestContextExporter;
import com.linecorp.armeria.common.logging.RequestContextExporterBuilder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

/**
 * A <a href="https://logback.qos.ch/">Logback</a> {@link Appender} that exports the properties of the current
 * {@link RequestContext} to {@link MDC}.
 *
 * <p>Read '<a href="https://line.github.io/armeria/docs/advanced-logging">Logging contextual information</a>'
 * for more information.
 */
public final class RequestContextExportingAppender
        extends UnsynchronizedAppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {

    static {
        if (InternalLoggerFactory.getDefaultFactory() == null) {
            // Can happen due to initialization order.
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }

    private final AppenderAttachableImpl<ILoggingEvent> aai = new AppenderAttachableImpl<>();
    private final RequestContextExporterBuilder builder = RequestContextExporter.builder();
    @Nullable
    private RequestContextExporter exporter;

    @VisibleForTesting
    RequestContextExporter exporter() {
        checkState(exporter != null);
        return exporter;
    }

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     */
    public void addBuiltIn(BuiltInProperty property) {
        ensureNotStarted();
        builder.addBuiltIn(property);
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     */
    public void addAttribute(String alias, AttributeKey<?> attrKey) {
        ensureNotStarted();
        builder.addAttribute(alias, attrKey);
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     * @param stringifier the {@link Function} that converts the attribute value into a {@link String}
     */
    public void addAttribute(String alias, AttributeKey<?> attrKey, Function<?, String> stringifier) {
        ensureNotStarted();
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        requireNonNull(stringifier, "stringifier");
        builder.addAttribute(alias, attrKey, stringifier);
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public void addRequestHeader(CharSequence name) {
        ensureNotStarted();
        requireNonNull(name, "name");
        builder.addRequestHeader(name);
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public void addResponseHeader(CharSequence name) {
        ensureNotStarted();
        requireNonNull(name, "name");
        builder.addResponseHeader(name);
    }

    /**
     * Adds the property represented by the specified MDC key to the export list.
     * Note: this method is meant to be used for XML configuration.
     * Use {@code add*()} methods instead.
     */
    public void setExport(String mdcKey) {
        requireNonNull(mdcKey, "mdcKey");
        builder.addKeyPattern(mdcKey);
    }

    /**
     * Adds the properties represented by the specified comma-separated MDC keys to the export list.
     * Note: this method is meant to be used for XML configuration.
     * Use {@code add*()} methods instead.
     */
    public void setExports(String mdcKeys) {
        requireNonNull(mdcKeys, "mdcKeys");
        builder.addKeyPattern(mdcKeys);
    }

    private void ensureNotStarted() {
        if (isStarted()) {
            throw new IllegalStateException("can't update the export list once started");
        }
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (exporter == null) {
            exporter = builder.build();
        }
        final Map<String, String> contextMap = exporter.export();
        if (!contextMap.isEmpty()) {
            final Map<String, String> originalMdcMap = eventObject.getMDCPropertyMap();
            final Map<String, String> mdcMap;

            if (!originalMdcMap.isEmpty()) {
                mdcMap = new UnionMap<>(contextMap, originalMdcMap);
            } else {
                mdcMap = contextMap;
            }
            eventObject = new LoggingEventWrapper(eventObject, mdcMap);
        }
        aai.appendLoopOnAppenders(eventObject);
    }

    @Override
    public void start() {
        if (!aai.iteratorForAppenders().hasNext()) {
            addWarn("No appender was attached to " + getClass().getSimpleName() + '.');
        }
        super.start();
    }

    @Override
    public void stop() {
        try {
            aai.detachAndStopAllAppenders();
        } finally {
            super.stop();
        }
    }

    @Override
    public void addAppender(Appender<ILoggingEvent> newAppender) {
        aai.addAppender(newAppender);
    }

    @Override
    public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
        return aai.iteratorForAppenders();
    }

    @Override
    public Appender<ILoggingEvent> getAppender(String name) {
        return aai.getAppender(name);
    }

    @Override
    public boolean isAttached(Appender<ILoggingEvent> appender) {
        return aai.isAttached(appender);
    }

    @Override
    public void detachAndStopAllAppenders() {
        aai.detachAndStopAllAppenders();
    }

    @Override
    public boolean detachAppender(Appender<ILoggingEvent> appender) {
        return aai.detachAppender(appender);
    }

    @Override
    public boolean detachAppender(String name) {
        return aai.detachAppender(name);
    }

    private static final class LoggingEventWrapper implements ILoggingEvent {
        private final ILoggingEvent event;
        private final Map<String, String> mdcPropertyMap;
        @Nullable
        private final LoggerContextVO vo;

        LoggingEventWrapper(ILoggingEvent event, Map<String, String> mdcPropertyMap) {
            this.event = event;
            this.mdcPropertyMap = mdcPropertyMap;

            final LoggerContextVO oldVo = event.getLoggerContextVO();
            if (oldVo != null) {
                vo = new LoggerContextVO(oldVo.getName(), mdcPropertyMap, oldVo.getBirthTime());
            } else {
                vo = null;
            }
        }

        @Override
        public Object[] getArgumentArray() {
            return event.getArgumentArray();
        }

        @Override
        public Level getLevel() {
            return event.getLevel();
        }

        @Override
        public String getLoggerName() {
            return event.getLoggerName();
        }

        @Override
        public String getThreadName() {
            return event.getThreadName();
        }

        @Override
        public IThrowableProxy getThrowableProxy() {
            return event.getThrowableProxy();
        }

        @Override
        public void prepareForDeferredProcessing() {
            event.prepareForDeferredProcessing();
        }

        @Override
        @Nullable
        public LoggerContextVO getLoggerContextVO() {
            return vo;
        }

        @Override
        public String getMessage() {
            return event.getMessage();
        }

        @Override
        public long getTimeStamp() {
            return event.getTimeStamp();
        }

        @Override
        public StackTraceElement[] getCallerData() {
            return event.getCallerData();
        }

        @Override
        public boolean hasCallerData() {
            return event.hasCallerData();
        }

        @Override
        public Marker getMarker() {
            return event.getMarker();
        }

        @Override
        public String getFormattedMessage() {
            return event.getFormattedMessage();
        }

        @Override
        public Map<String, String> getMDCPropertyMap() {
            return mdcPropertyMap;
        }

        /**
         * A synonym for {@link #getMDCPropertyMap}.
         * @deprecated Use {@link #getMDCPropertyMap()}.
         */
        @Override
        @Deprecated
        public Map<String, String> getMdc() {
            return event.getMDCPropertyMap();
        }

        @Override
        public String toString() {
            return event.toString();
        }
    }
}
