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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.ExportGroupBuilder.ExportEntry;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * Exports the specified properties from current {@link RequestContext} to {@link Map}.
 */
public final class RequestContextExporter {

    @SuppressWarnings("rawtypes")
    private static final ExportEntry[] EMPTY_EXPORT_ENTRIES = new ExportEntry[0];

    @VisibleForTesting
    final AttributeKey<State> stateAttributeKey;

    /**
     * Returns a newly created {@link RequestContextExporterBuilder}.
     */
    public static RequestContextExporterBuilder builder() {
        return new RequestContextExporterBuilder();
    }

    @Nullable
    private final BuiltInProperties builtInProperties;
    @Nullable
    private final ExportEntry<BuiltInProperty>[] builtInPropertyArray;
    @Nullable
    private final ExportEntry<AttributeKey<?>>[] attrs;
    private final int numAttrs;
    @Nullable
    private final ExportEntry<AsciiString>[] reqHeaders;
    @Nullable
    private final ExportEntry<AsciiString>[] resHeaders;

    RequestContextExporter(String name,
                           Set<ExportEntry<BuiltInProperty>> builtInPropertySet,
                           Set<ExportEntry<AttributeKey<?>>> attrs,
                           Set<ExportEntry<AsciiString>> reqHeaders,
                           Set<ExportEntry<AsciiString>> resHeaders) {
        stateAttributeKey = AttributeKey.valueOf(RequestContextExporter.class, name + "_STATE");
        if (!builtInPropertySet.isEmpty()) {
            builtInProperties = new BuiltInProperties();
            builtInPropertyArray = builtInPropertySet.toArray(EMPTY_EXPORT_ENTRIES);
            for (ExportEntry<BuiltInProperty> entry : builtInPropertyArray) {
                builtInProperties.add(entry.key);
            }
        } else {
            builtInProperties = null;
            builtInPropertyArray = null;
        }

        if (!attrs.isEmpty()) {
            this.attrs = attrs.toArray(EMPTY_EXPORT_ENTRIES);
            numAttrs = this.attrs.length;
        } else {
            this.attrs = null;
            numAttrs = 0;
        }

        if (!reqHeaders.isEmpty()) {
            this.reqHeaders = reqHeaders.toArray(EMPTY_EXPORT_ENTRIES);
        } else {
            this.reqHeaders = null;
        }

        if (!resHeaders.isEmpty()) {
            this.resHeaders = resHeaders.toArray(EMPTY_EXPORT_ENTRIES);
        } else {
            this.resHeaders = null;
        }
    }

    /**
     * Returns {@code true} if the specified {@link AttributeKey} is in the export list.
     */
    public boolean containsAttribute(AttributeKey<?> key) {
        requireNonNull(key, "key");
        if (attrs == null) {
            return false;
        }
        return Arrays.stream(attrs).anyMatch(e -> e.key.equals(key));
    }

    /**
     * Returns {@code true} if the specified HTTP request header name is in the export list.
     */
    public boolean containsRequestHeader(CharSequence name) {
        requireNonNull(name, "name");
        if (reqHeaders == null) {
            return false;
        }
        return Arrays.stream(reqHeaders).anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    /**
     * Returns {@code true} if the specified HTTP response header name is in the export list.
     */
    public boolean containsResponseHeader(CharSequence name) {
        requireNonNull(name, "name");
        if (resHeaders == null) {
            return false;
        }
        return Arrays.stream(resHeaders).anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    /**
     * Returns {@code true} if the specified {@link BuiltInProperty} is in the export list.
     */
    public boolean containsBuiltIn(BuiltInProperty property) {
        requireNonNull(property, "property");
        if (builtInProperties == null) {
            return false;
        }
        return builtInProperties.contains(property);
    }

    /**
     * Returns all {@link BuiltInProperty}s in the export list.
     */
    public Set<BuiltInProperty> builtIns() {
        if (builtInPropertyArray == null) {
            return ImmutableSet.of();
        }
        return Arrays.stream(builtInPropertyArray).map(entry -> entry.key).collect(toImmutableSet());
    }

    /**
     * Returns all {@link AttributeKey}s in the export list.
     *
     * @return the {@link Map} whose key is an alias and value is an {@link AttributeKey}
     */
    public Map<String, AttributeKey<?>> attributes() {
        if (attrs == null) {
            return ImmutableMap.of();
        }
        return Arrays.stream(attrs).collect(toImmutableMap(e -> e.exportKey, e -> e.key));
    }

    /**
     * Returns all HTTP request header names in the export list.
     */
    public Set<AsciiString> requestHeaders() {
        if (reqHeaders == null) {
            return ImmutableSet.of();
        }
        return Arrays.stream(reqHeaders).map(e -> e.key).collect(toImmutableSet());
    }

    /**
     * Returns all HTTP response header names in the export list.
     */
    public Set<AsciiString> responseHeaders() {
        if (resHeaders == null) {
            return ImmutableSet.of();
        }
        return Arrays.stream(resHeaders).map(e -> e.key).collect(toImmutableSet());
    }

    /**
     * Returns a {@link Map} whose key is an export key set through {@code add*()} in
     * {@link RequestContextExporterBuilder} and value is extracted from {@link RequestContext}.
     * Note that this method returns an empty {@link Map} if current {@link RequestContext} is {@code null}.
     */
    public Map<String, String> export() {
        final RequestContext ctx = RequestContext.currentOrNull();
        return ctx != null ? export(ctx) : ImmutableMap.of();
    }

    /**
     * Returns a {@link Map} whose key is an export key set through {@code add*()} in
     * {@link RequestContextExporterBuilder} and value is extracted from the specified {@link RequestContext}.
     */
    public Map<String, String> export(RequestContext ctx) {
        requireNonNull(ctx, "ctx");

        final State state = state(ctx);
        final RequestLogAccess log = ctx.log();
        boolean needsUpdate = false;

        // Needs to update if availabilityStamp has changed.
        // Also updates `State.availabilityStamp` while checking.
        final int availabilityStamp = log.availabilityStamp();
        if (state.availabilityStamp != availabilityStamp) {
            state.availabilityStamp = availabilityStamp;
            needsUpdate = true;
        }

        // Needs to update if any attributes have changed.
        // Also updates `State.attrValues` while scanning.
        if (attrs != null) {
            assert state.attrValues != null;
            for (int i = 0; i < attrs.length; i++) {
                final Object newValue = ctx.attr(attrs[i].key);
                if (!needsUpdate) {
                    final Object oldValue = state.attrValues[i];
                    needsUpdate = !Objects.equals(oldValue, newValue);
                }
                state.attrValues[i] = newValue;
            }
        }

        if (needsUpdate) {
            export(state, log.partial());
        }

        // Create a copy of 'state' to avoid the race between:
        // - the delegate appenders who iterate over the MDC map and
        // - this class who updates 'state'.
        return state.clone();
    }

    private void export(State state, RequestLog log) {
        exportBuiltIns(state, log);
        exportAttributes(state);
        exportRequestHeaders(state, log);
        exportResponseHeaders(state, log);
    }

    private void exportBuiltIns(State state, RequestLog log) {
        if (builtInPropertyArray != null) {
            for (final ExportEntry<BuiltInProperty> entry : builtInPropertyArray) {
                final String value = entry.key.converter.apply(log);
                if (value != null) {
                    state.put(entry.exportKey, value);
                }
            }
        }
    }

    private void exportAttributes(State state) {
        if (attrs == null) {
            return;
        }

        assert state.attrValues != null;
        for (int i = 0; i < numAttrs; i++) {
            final ExportEntry<AttributeKey<?>> e = attrs[i];
            putStringifiedProperty(state, e, state.attrValues[i]);
        }
    }

    private void exportRequestHeaders(State state, RequestLog log) {
        if (reqHeaders == null || !log.isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
            return;
        }

        exportHeaders(state, log.requestHeaders(), reqHeaders);
    }

    private void exportResponseHeaders(State state, RequestLog log) {
        if (resHeaders == null || !log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            return;
        }

        exportHeaders(state, log.responseHeaders(), resHeaders);
    }

    private static void exportHeaders(State state, HttpHeaders headers,
                                      ExportEntry<AsciiString>[] requiredHeaderNames) {
        for (ExportEntry<AsciiString> e : requiredHeaderNames) {
            putStringifiedProperty(state, e, headers.get(e.key));
        }
    }

    private static void putStringifiedProperty(State state, ExportEntry<?> entry, @Nullable Object value) {
        if (value != null) {
            final String valueStr = entry.stringify(value);
            if (valueStr != null) {
                state.put(entry.exportKey, valueStr);
                return;
            }
        }

        // Remove the value if it exists already.
        state.remove(entry.exportKey);
    }

    private State state(RequestContext ctx) {
        final State state = ctx.ownAttr(stateAttributeKey);
        if (state != null) {
            return state;
        }

        final State newState = new State(numAttrs);
        ctx.setAttr(stateAttributeKey, newState);
        return newState;
    }

    private static final class State extends Object2ObjectOpenHashMap<String, String> {
        private static final long serialVersionUID = -7084248226635055988L;

        int availabilityStamp = -1;
        @Nullable
        final Object[] attrValues;

        State(int numAttrs) {
            attrValues = numAttrs != 0 ? new Object[numAttrs] : null;
        }
    }
}
