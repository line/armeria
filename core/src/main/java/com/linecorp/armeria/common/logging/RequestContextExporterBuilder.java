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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.logging.RequestContextExporter.ExportEntry;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

/**
 * Builds a new {@link RequestContextExporter}.
 */
public final class RequestContextExporterBuilder {

    static final String PREFIX_ATTRS = "attrs.";
    private static final String PREFIX_HTTP_REQ_HEADERS = "req.http_headers.";
    private static final String PREFIX_HTTP_RES_HEADERS = "res.http_headers.";

    private final Set<BuiltInProperty> builtIns = EnumSet.noneOf(BuiltInProperty.class);
    private final Set<ExportEntry<AttributeKey<?>>> attrs = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> httpReqHeaders = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> httpResHeaders = new HashSet<>();

    RequestContextExporterBuilder() {}

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     */
    public RequestContextExporterBuilder addBuiltIn(BuiltInProperty property) {
        builtIns.add(requireNonNull(property, "property"));
        return this;
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     */
    public RequestContextExporterBuilder addAttribute(String alias, AttributeKey<?> attrKey) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        attrs.add(new ExportEntry<>(attrKey, PREFIX_ATTRS + alias, null));
        return this;
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     * @param stringifier the {@link Function} that converts the attribute value into a {@link String}
     */
    public RequestContextExporterBuilder addAttribute(String alias, AttributeKey<?> attrKey,
                                                      Function<?, String> stringifier) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        requireNonNull(stringifier, "stringifier");
        attrs.add(new ExportEntry<>(attrKey, PREFIX_ATTRS + alias, stringifier));
        return this;
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public RequestContextExporterBuilder addHttpRequestHeader(CharSequence name) {
        addHttpHeader(PREFIX_HTTP_REQ_HEADERS, httpReqHeaders, requireNonNull(name, "name"));
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public RequestContextExporterBuilder addHttpResponseHeader(CharSequence name) {
        addHttpHeader(PREFIX_HTTP_RES_HEADERS, httpResHeaders, requireNonNull(name, "name"));
        return this;
    }

    private static void addHttpHeader(
            String mdcKeyPrefix, Set<ExportEntry<AsciiString>> httpHeaders, CharSequence name) {
        final AsciiString key = toHeaderName(name);
        final String value = mdcKeyPrefix + key;
        httpHeaders.add(new ExportEntry<>(key, value, null));
    }

    private static AsciiString toHeaderName(CharSequence name) {
        return HttpHeaderNames.of(requireNonNull(name, "name").toString());
    }

    /**
     * Adds the property represented by the specified key pattern to the export list. Please refer to the
     * <a href="https://line.github.io/armeria/advanced-logging.html">Logging contextual information</a>
     * in order to learn how to specify a key pattern.
     */
    public RequestContextExporterBuilder addKeyPattern(String keyPattern) {
        requireNonNull(keyPattern, "keyPattern");

        final List<BuiltInProperty> builtInPropertyList = BuiltInProperty.findByKeyPattern(keyPattern);
        if (!builtInPropertyList.isEmpty()) {
            builtIns.addAll(builtInPropertyList);
            return this;
        }

        if (keyPattern.contains(BuiltInProperty.WILDCARD_STR)) {
            return this;
        }

        if (keyPattern.startsWith(PREFIX_ATTRS)) {
            final String[] components = keyPattern.split(":");
            switch (components.length) {
                case 2:
                    addAttribute(components[0].substring(PREFIX_ATTRS.length()),
                                 AttributeKey.valueOf(components[1]));
                    break;
                case 3:
                    final Function<?, String> stringifier = newStringifier(keyPattern, components[2]);

                    addAttribute(components[0].substring(PREFIX_ATTRS.length()),
                                 AttributeKey.valueOf(components[1]),
                                 stringifier);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "invalid attribute export: " + keyPattern +
                            " (expected: attrs.<alias>:<AttributeKey.name>[:<FQCN of Function<?, String>>])");
            }
            return this;
        }

        if (keyPattern.startsWith(PREFIX_HTTP_REQ_HEADERS)) {
            addHttpRequestHeader(keyPattern.substring(PREFIX_HTTP_REQ_HEADERS.length()));
            return this;
        }

        if (keyPattern.startsWith(PREFIX_HTTP_RES_HEADERS)) {
            addHttpResponseHeader(keyPattern.substring(PREFIX_HTTP_RES_HEADERS.length()));
            return this;
        }

        throw new IllegalArgumentException("unknown key pattern: " + keyPattern);
    }

    @SuppressWarnings("unchecked")
    private Function<?, String> newStringifier(String keyPattern, String className) {
        final Function<?, String> stringifier;
        try {
            stringifier = (Function<?, String>)
                    Class.forName(className, true, getClass().getClassLoader())
                         .getDeclaredConstructor()
                         .newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to instantiate a stringifier function: " +
                                               keyPattern, e);
        }
        return stringifier;
    }

    /**
     * Returns a newly-created {@link RequestContextExporter} instance.
     */
    public RequestContextExporter build() {
        return new RequestContextExporter(builtIns, attrs, httpReqHeaders, httpResHeaders);
    }
}
