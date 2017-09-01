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

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.MDC;
import org.slf4j.Marker;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import io.netty.util.AsciiString;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * A <a href="https://logback.qos.ch/">Logback</a> {@link Appender} that exports the properties of the current
 * {@link RequestContext} to {@link MDC}.
 *
 * <p>Read '<a href="https://line.github.io/armeria/server-basics.html">Logging contextual information</a>'
 * for more information.
 */
public class RequestContextExportingAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
                                             implements AppenderAttachable<ILoggingEvent> {
    static {
        if (InternalLoggerFactory.getDefaultFactory() == null) {
            // Can happen due to initialization order.
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }

    private static final AttributeKey<State> STATE =
            AttributeKey.valueOf(RequestContextExportingAppender.class, "STATE");

    private RequestContextExporter exporter;

    private final AppenderAttachableImpl<ILoggingEvent> aai = new AppenderAttachableImpl<>();
    private final RequestContextExporterBuilder builder = new RequestContextExporterBuilder();

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     */
    public void addBuiltIn(BuiltInProperty property) {
        ensureNotStarted();
        builder.addBuiltIn(property);
    }

    /**
     * Returns {@code true} if the specified {@link BuiltInProperty} is in the export list.
     */
    public boolean containsBuiltIn(BuiltInProperty property) {
        return builder.containsBuiltIn(property);
    }

    /**
     * Returns all {@link BuiltInProperty}s in the export list.
     */
    public Set<BuiltInProperty> getBuiltIns() {
        return builder.getBuiltIns();
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
        builder.addAttribute(alias, attrKey, stringifier);
    }

    /**
     * Returns {@code true} if the specified {@link AttributeKey} is in the export list.
     */
    public boolean containsAttribute(AttributeKey<?> key) {
        requireNonNull(key, "key");
        return builder.containsAttribute(key);
    }

    /**
     * Returns all {@link AttributeKey}s in the export list.
     *
     * @return the {@link Map} whose key is an alias and value is an {@link AttributeKey}
     */
    public Map<String, AttributeKey<?>> getAttributes() {
        return builder.getAttributes();
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public void addHttpRequestHeader(CharSequence name) {
        ensureNotStarted();
        builder.addHttpRequestHeader(name);
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public void addHttpResponseHeader(CharSequence name) {
        ensureNotStarted();
        builder.addHttpResponseHeader(name);
    }

    /**
     * Returns {@code true} if the specified HTTP request header name is in the export list.
     */
    public boolean containsHttpRequestHeader(CharSequence name) {
        return builder.containsHttpRequestHeader(name);
    }

    /**
     * Returns {@code true} if the specified HTTP response header name is in the export list.
     */
    public boolean containsHttpResponseHeader(CharSequence name) {
        return builder.containsHttpResponseHeader(name);
    }

    /**
     * Returns all HTTP request header names in the export list.
     */
    public Set<AsciiString> getHttpRequestHeaders() {
        return builder.getHttpRequestHeaders();
    }

    /**
     * Returns all HTTP response header names in the export list.
     */
    public Set<AsciiString> getHttpResponseHeaders() {
        return builder.getHttpResponseHeaders();
    }

    /**
     * Adds the property represented by the specified MDC key to the export list.
     * Note: this method is meant to be used for XML configuration.
     * Use {@code add*()} methods instead.
     */
    public void setExport(String mdcKey) {
        ensureNotStarted();
        builder.export(mdcKey);
    }

    /**
     * Adds the properties represented by the specified comma-separated MDC keys to the export list.
     * Note: this method is meant to be used for XML configuration.
     * Use {@code add*()} methods instead.
     */
    public void setExports(String mdcKeys) {
        ensureNotStarted();
        requireNonNull(mdcKeys, "mdcKeys");
        Arrays.stream(mdcKeys.split(",")).map(String::trim).forEach(this::setExport);
    }

    private void ensureNotStarted() {
        if (exporter != null) {
            throw new IllegalStateException("can't update the export list once started");
        }
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        final RequestContext ctx = RequestContext.mapCurrent(Function.identity(), () -> null);
        if (ctx != null) {
            final State state = state(ctx);
            final RequestLog log = ctx.log();
            final Set<RequestLogAvailability> availabilities = log.availabilities();

            // Note: This equality check is extremely fast.
            //       See RequestLogAvailabilitySet for more information.
            if (!availabilities.equals(state.availabilities)) {
                state.availabilities = availabilities;
                export(state, ctx, log);
            }

            final Map<String, String> originalMdcMap = eventObject.getMDCPropertyMap();
            final Map<String, String> mdcMap;

            // Create a copy of 'state' to avoid the race between:
            // - the delegate appenders who iterate over the MDC map and
            // - this class who update 'state'.
            if (!originalMdcMap.isEmpty()) {
                mdcMap = new UnionMap<>(state.clone(), originalMdcMap);
            } else {
                mdcMap = state.clone();
            }

            eventObject = new LoggingEventWrapper(eventObject, mdcMap);
        }

        aai.appendLoopOnAppenders(eventObject);
    }

    private static State state(RequestContext ctx) {
        final Attribute<State> attr = ctx.attr(STATE);
        final State state = attr.get();
        if (state == null) {
            State newState = new State();
            State oldState = attr.setIfAbsent(newState);
            if (oldState != null) {
                return oldState;
            } else {
                return newState;
            }
        }
        return state;
    }

    /**
     * Exports the necessary properties to {@link MDC}. By default, this method exports all properties added
     * to the export list via {@code add*()} calls and {@code <export />} tags. Override this method to export
     * additional properties.
     */
    protected void export(Map<String, String> out, RequestContext ctx, @Nullable RequestLog log) {
        exporter.export(out, ctx, log);
    }

    @Override
    public void start() {
        if (!aai.iteratorForAppenders().hasNext()) {
            addWarn("No appender was attached to " + getClass().getSimpleName() + '.');
        }
        if (exporter == null) {
            exporter = builder.build();
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

    private static final class State extends Object2ObjectOpenHashMap<String, String> {
        private static final long serialVersionUID = -7084248226635055988L;

        Set<RequestLogAvailability> availabilities;
    }

    private static final class LoggingEventWrapper implements ILoggingEvent {
        private final ILoggingEvent event;
        private final Map<String, String> mdcPropertyMap;
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

        @Override
        public Map<String, String> getMdc() {
            return event.getMdc();
        }

        @Override
        public String toString() {
            return event.toString();
        }
    }
}
