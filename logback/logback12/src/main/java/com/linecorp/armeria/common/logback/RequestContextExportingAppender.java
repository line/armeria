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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.MDC;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.BuiltInProperty;
import com.linecorp.armeria.common.logging.RequestContextExporter;
import com.linecorp.armeria.common.logging.RequestContextExporterBuilder;
import com.linecorp.armeria.internal.common.FlagsLoaded;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.net.AbstractSocketAppender;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

/**
 * A <a href="https://logback.qos.ch/">Logback</a> {@link Appender} that exports the properties of the current
 * {@link RequestContext} to {@link MDC}.
 *
 * <p>Read '<a href="https://armeria.dev/docs/advanced-logging">Logging contextual information</a>'
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

    private static final Splitter KEY_SPLITTER = Splitter.on(',').trimResults();

    private final AppenderAttachableImpl<ILoggingEvent> aai = new AppenderAttachableImpl<>();
    @Nullable
    private RequestContextExporter exporter;
    private boolean needsHashMap;
    private Consumer<RequestContextExporterBuilder> customizer = unused -> {};

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
        configureExporter(builder -> builder.builtIn(property));
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     */
    public void addAttribute(String alias, AttributeKey<?> attrKey) {
        ensureNotStarted();
        configureExporter(builder -> builder.attr(alias, attrKey));
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
        configureExporter(builder -> builder.attr(alias, attrKey, stringifier));
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public void addRequestHeader(CharSequence name) {
        ensureNotStarted();
        requireNonNull(name, "name");
        configureExporter(builder -> builder.requestHeader(name));
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public void addResponseHeader(CharSequence name) {
        ensureNotStarted();
        requireNonNull(name, "name");
        configureExporter(builder -> builder.responseHeader(name));
    }

    /**
     * Specifies a prefix of the default export group.
     * Note: this method is meant to be used for XML configuration.
     */
    public void setPrefix(String prefix) {
        requireNonNull(prefix, "prefix");
        checkArgument(!prefix.isEmpty(), "prefix must not be empty");
        configureExporter(builder -> builder.prefix(prefix));
    }

    /**
     * Adds the property represented by the specified MDC key to the export list.
     * Note: this method is meant to be used for XML configuration.
     * Use {@code add*()} methods instead.
     */
    public void setExport(String mdcKey) {
        requireNonNull(mdcKey, "mdcKey");
        checkArgument(!mdcKey.isEmpty(), "mdcKey must not be empty");
        configureExporter(builder -> builder.keyPattern(mdcKey));
    }

    /**
     * Adds the properties represented by the specified comma-separated MDC keys to the export list.
     * Note: this method is meant to be used for XML configuration.
     * Use {@code add*()} methods instead.
     */
    public void setExports(String mdcKeys) {
        requireNonNull(mdcKeys, "mdcKeys");
        checkArgument(!mdcKeys.isEmpty(), "mdcKeys must not be empty");
        configureExporter(builder -> {
            KEY_SPLITTER.split(mdcKeys)
                        .forEach(mdcKey -> {
                            checkArgument(!mdcKey.isEmpty(), "comma-separated MDC key must not be empty");
                            builder.keyPattern(mdcKey);
                        });
        });
    }

    /**
     * Adds the export group.
     * Note: this method is meant to be used for XML configuration.
     */
    public void setExportGroup(ExportGroupConfig exportGroupConfiguration) {
        requireNonNull(exportGroupConfiguration, "exportGroupConfiguration");
        configureExporter(builder -> builder.exportGroup(exportGroupConfiguration.build()));
    }

    private void ensureNotStarted() {
        if (isStarted()) {
            throw new IllegalStateException("can't update the export list once started");
        }
    }

    /**
     * Stores the specified {@link RequestContextExporterBuilder} customizer to be lazily applied when
     * the exporter is built.
     */
    private void configureExporter(Consumer<RequestContextExporterBuilder> customizer) {
        requireNonNull(customizer, "customizer");
        this.customizer = this.customizer.andThen(customizer);
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (exporter == null) {
            if (!FlagsLoaded.get()) {
                // It is possible that requestContextStorageProvider hasn't been initialized yet
                // due to static variable circular dependency.
                // Most notably, this can happen when logs are appended while trying to initialize
                // Flags#requestContextStorageProvider.
                aai.appendLoopOnAppenders(eventObject);
                return;
            }
            // Build the exporter lazily to prevent the customizer from initializing Flags.
            // See: https://github.com/line/armeria/issues/5327
            final RequestContextExporterBuilder builder = RequestContextExporter.builder();
            customizer.accept(builder);
            exporter = builder.build();
        }
        final Map<String, String> contextMap = exporter.export();
        if (!contextMap.isEmpty()) {
            final Map<String, String> originalMdcMap = eventObject.getMDCPropertyMap();
            final Map<String, String> mdcMap = prepareMdcMap(contextMap, originalMdcMap);
            eventObject = new LoggingEventWrapper(eventObject, mdcMap);
        }
        aai.appendLoopOnAppenders(eventObject);
    }

    private Map<String, String> prepareMdcMap(Map<String, String> contextMap,
                                              Map<String, String> originalMdcMap) {
        if (needsHashMap) {
            final Map<String, String> mdcMap = new HashMap<>(contextMap);
            mdcMap.putAll(originalMdcMap);
            return mdcMap;
        }
        if (!originalMdcMap.isEmpty()) {
            return new UnionMap<>(contextMap, originalMdcMap);
        }
        return contextMap;
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
        // When SocketAppender is used and event object contains classes
        // that are not on whitelist, HardenedObjectInputStream raises
        // InvalidClassException: Unauthorized deserialization attempt.
        needsHashMap = isSocketAppender(newAppender);
        aai.addAppender(newAppender);
    }

    @SuppressWarnings("unchecked")
    private boolean isSocketAppender(Appender<ILoggingEvent> appender) {
        if (appender instanceof AbstractSocketAppender) {
            return true;
        }
        if (appender instanceof AppenderAttachable) {
            for (final Iterator<Appender<ILoggingEvent>> i = ((AppenderAttachable<ILoggingEvent>) appender)
                    .iteratorForAppenders(); i.hasNext();) {
                if (isSocketAppender(i.next())) {
                    return true;
                }
            }
        }
        return false;
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
}
