/*
 * Copyright 2019 LINE Corporation
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

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.MDC;

import com.linecorp.armeria.common.RequestContext;

import io.netty.util.AsciiString;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * A wrapper class which adds the specified {@link BuiltInProperty}, {@link AttributeKey} and
 * HTTP response header name to the export list. This exports all properties added to the export list
 * via {@code add*()} calls and {@code <export />} tags.
 */
public class RequestContextAwareExporter {

    private static final AttributeKey<State> STATE =
            AttributeKey.valueOf(RequestContextAwareExporter.class, "STATE");

    @Nullable
    private RequestContextExporter exporter;
    private final RequestContextExporterBuilder builder = new RequestContextExporterBuilder();

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     */
    public void addBuiltIn(BuiltInProperty property) {
        builder.addBuiltIn(requireNonNull(property, "property"));
    }

    /**
     * Returns {@code true} if the specified {@link BuiltInProperty} is in the export list.
     */
    public boolean containsBuiltIn(BuiltInProperty property) {
        return builder.containsBuiltIn(requireNonNull(property, "property"));
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
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
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
        requireNonNull(name, "name");
        builder.addHttpRequestHeader(name);
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public void addHttpResponseHeader(CharSequence name) {
        ensureNotStarted();
        requireNonNull(name, "name");
        builder.addHttpResponseHeader(name);
    }

    /**
     * Returns {@code true} if the specified HTTP request header name is in the export list.
     */
    public boolean containsHttpRequestHeader(CharSequence name) {
        requireNonNull(name, "name");
        return builder.containsHttpRequestHeader(name);
    }

    /**
     * Returns {@code true} if the specified HTTP response header name is in the export list.
     */
    public boolean containsHttpResponseHeader(CharSequence name) {
        requireNonNull(name, "name");
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
        requireNonNull(mdcKey, "mdcKey");
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

    /**
     * Exports the necessary properties to {@link MDC}. By default, this method exports all properties added
     * to the export list via {@code add*()} calls and {@code <export />} tags. Override this method to export
     * additional properties.
     */
    public void export(Map<String, String> out, RequestContext ctx, RequestLog log) {
        requireNonNull(out, "out");
        requireNonNull(ctx, "ctx");
        requireNonNull(log, "log");
        assert exporter != null;
        exporter.export(out, ctx, log);
    }

    private void createExporter() {
        if (exporter == null) {
            exporter = builder.build();
        }
    }

    /**
     * Returns a {@link Map} whose key is set through {@code <export />} and the value is exported
     * from {@link RequestContext} and {@link RequestLog}.
     * Note that: this method returns {@code null} if current {@link RequestContext} is {@code null}.
     */
    @Nullable
    public Map<String, String> exports() {
        final RequestContext ctx = RequestContext.currentOrNull();
        if (ctx != null) {
            createExporter();
            final State state = state(ctx);
            final RequestLog log = ctx.log();
            final Set<RequestLogAvailability> availabilities = log.availabilities();

            // Note: This equality check is extremely fast.
            //       See RequestLogAvailabilitySet for more information.
            if (!availabilities.equals(state.availabilities)) {
                state.availabilities = availabilities;
                export(state, ctx, log);
            }
            return state.clone();
        }
        return null;
    }

    private void ensureNotStarted() {
        if (exporter != null) {
            throw new IllegalStateException("can't update the export list once started");
        }
    }

    private static State state(RequestContext ctx) {
        final Attribute<State> attr = ctx.attr(STATE);
        final State state = attr.get();
        if (state == null) {
            final State newState = new State();
            final State oldState = attr.setIfAbsent(newState);
            if (oldState != null) {
                return oldState;
            } else {
                return newState;
            }
        }
        return state;
    }

    private static final class State extends Object2ObjectOpenHashMap<String, String> {
        private static final long serialVersionUID = -7084248226635055988L;

        @Nullable
        Set<RequestLogAvailability> availabilities;
    }
}
