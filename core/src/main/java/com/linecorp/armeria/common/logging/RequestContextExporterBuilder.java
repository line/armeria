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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.logging.RequestContextExporter.ExportEntry;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

/**
 * Builds a new {@link RequestContextExporter}.
 */
public final class RequestContextExporterBuilder {

    static final String PREFIX_ATTRS = "attrs.";
    private static final String ATTR_NAMESPACE = "attr:";
    private static final String PREFIX_HTTP_REQ_HEADERS = "req.http_headers.";
    private static final String PREFIX_HTTP_RES_HEADERS = "res.http_headers.";

    private final Set<ExportEntry<BuiltInProperty>> builtIns = new HashSet<>();
    private final Set<ExportEntry<AttributeKey<?>>> attrs = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> httpReqHeaders = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> httpResHeaders = new HashSet<>();

    RequestContextExporterBuilder() {}

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     * The {@link BuiltInProperty#key} will be used for the export key.
     */
    public RequestContextExporterBuilder addBuiltIn(BuiltInProperty property) {
        requireNonNull(property, "property");
        builtIns.add(new ExportEntry<>(property, property.key));
        return this;
    }

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     * The specified {@code alias} will be used for the export key.
     */
    public RequestContextExporterBuilder addBuiltIn(BuiltInProperty property, String alias) {
        requireNonNull(property, "property");
        requireNonNull(alias, "alias");
        builtIns.add(new ExportEntry<>(property, alias));
        return this;
    }

    /**
     * Returns {@code true} if the specified {@link BuiltInProperty} is in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     */
    @Deprecated
    public boolean containsBuiltIn(BuiltInProperty property) {
        requireNonNull(property, "property");
        return builtIns.stream().anyMatch(entry -> entry.key == property);
    }

    /**
     * Returns all {@link BuiltInProperty}s in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     */
    @Deprecated
    public Set<BuiltInProperty> getBuiltIns() {
        return builtIns.stream().map(entry -> entry.key).collect(toImmutableSet());
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     * The specified {@code alias} is used for the export key.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     */
    public RequestContextExporterBuilder addAttribute(String alias, AttributeKey<?> attrKey) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        attrs.add(new ExportEntry<>(attrKey, alias));
        return this;
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     * The specified {@code alias} is used for the export key.
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
        attrs.add(new ExportEntry<>(attrKey, alias, stringifier));
        return this;
    }

    /**
     * Returns {@code true} if the specified {@link AttributeKey} is in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     */
    @Deprecated
    public boolean containsAttribute(AttributeKey<?> key) {
        requireNonNull(key, "key");
        return attrs.stream().anyMatch(e -> e.key.equals(key));
    }

    /**
     * Returns all {@link AttributeKey}s in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     *
     * @return the {@link Map} whose key is an alias and value is an {@link AttributeKey}
     */
    @Deprecated
    public Map<String, AttributeKey<?>> getAttributes() {
        return attrs.stream().collect(
                toImmutableMap(e -> {
                    if (e.exportKey.startsWith(PREFIX_ATTRS)) {
                        return e.exportKey.substring(PREFIX_ATTRS.length());
                    }
                    return e.exportKey;
                }, e -> e.key));
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public RequestContextExporterBuilder addHttpRequestHeader(CharSequence name) {
        final AsciiString key = toHeaderName(requireNonNull(name, "name"));
        final String exportKey = PREFIX_HTTP_REQ_HEADERS + key;
        httpReqHeaders.add(new ExportEntry<>(key, exportKey));
        return this;
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public RequestContextExporterBuilder addHttpRequestHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        httpReqHeaders.add(new ExportEntry<>(toHeaderName(headerName), alias));
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public RequestContextExporterBuilder addHttpResponseHeader(CharSequence name) {
        final AsciiString key = toHeaderName(requireNonNull(name, "name"));
        final String exportKey = PREFIX_HTTP_RES_HEADERS + key;
        httpResHeaders.add(new ExportEntry<>(key, exportKey));
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public RequestContextExporterBuilder addHttpResponseHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        httpResHeaders.add(new ExportEntry<>(toHeaderName(headerName), alias));
        return this;
    }

    /**
     * Returns {@code true} if the specified HTTP request header name is in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     */
    @Deprecated
    public boolean containsHttpRequestHeader(CharSequence name) {
        requireNonNull(name, "name");
        return httpReqHeaders.stream().anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    /**
     * Returns {@code true} if the specified HTTP response header name is in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     */
    @Deprecated
    public boolean containsHttpResponseHeader(CharSequence name) {
        requireNonNull(name, "name");
        return httpResHeaders.stream().anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    private static AsciiString toHeaderName(CharSequence name) {
        return HttpHeaderNames.of(requireNonNull(name, "name").toString());
    }

    /**
     * Returns all HTTP request header names in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     */
    @Deprecated
    public Set<AsciiString> getHttpRequestHeaders() {
        return httpReqHeaders.stream().map(e -> e.key).collect(toImmutableSet());
    }

    /**
     * Returns all HTTP response header names in the export list.
     *
     * @deprecated This method will be removed without a replacement.
     */
    @Deprecated
    public Set<AsciiString> getHttpResponseHeaders() {
        return httpResHeaders.stream().map(e -> e.key).collect(toImmutableSet());
    }

    /**
     * Adds the property represented by the specified key pattern to the export list. Please refer to the
     * <a href="https://line.github.io/armeria/advanced-logging.html">Logging contextual information</a>
     * in order to learn how to specify a key pattern.
     */
    public RequestContextExporterBuilder addKeyPattern(String keyPattern) {
        requireNonNull(keyPattern, "keyPattern");

        final int exportKeyPos = keyPattern.indexOf('=');

        if (keyPattern.contains(BuiltInProperty.WILDCARD_STR)) {
            if (exportKeyPos > 0) {
                throw new IllegalArgumentException(
                        "A custom export key is unsupported for the wildcard: " + keyPattern);
            }
            BuiltInProperty.findByKeyPattern(keyPattern)
                           .stream().map(prop -> new ExportEntry<>(prop, prop.key))
                           .forEach(builtIns::add);
            return this;
        }

        String exportKey = null;
        if (exportKeyPos > 0) {
            exportKey = keyPattern.substring(0, exportKeyPos);
            keyPattern = keyPattern.substring(exportKeyPos + 1);
        }

        final BuiltInProperty property = BuiltInProperty.findByKey(keyPattern);
        if (property != null) {
            builtIns.add(new ExportEntry<>(property, exportKey != null ? exportKey : property.key));
            return this;
        }

        if (keyPattern.startsWith(PREFIX_ATTRS) || keyPattern.startsWith(ATTR_NAMESPACE)) {
            final ExportEntry<AttributeKey<?>> attrExportEntry = parseAttrPattern(keyPattern, exportKey);
            attrs.add(attrExportEntry);
            return this;
        }

        if (keyPattern.startsWith(PREFIX_HTTP_REQ_HEADERS)) {
            if (exportKey == null) {
                addHttpRequestHeader(keyPattern.substring(PREFIX_HTTP_REQ_HEADERS.length()));
            } else {
                addHttpRequestHeader(keyPattern.substring(PREFIX_HTTP_REQ_HEADERS.length()), exportKey);
            }
            return this;
        }

        if (keyPattern.startsWith(PREFIX_HTTP_RES_HEADERS)) {
            if (exportKey == null) {
                addHttpResponseHeader(keyPattern.substring(PREFIX_HTTP_RES_HEADERS.length()));
            } else {
                addHttpResponseHeader(keyPattern.substring(PREFIX_HTTP_RES_HEADERS.length()), exportKey);
            }
            return this;
        }

        throw new IllegalArgumentException("unknown key pattern: " + keyPattern);
    }

    private ExportEntry<AttributeKey<?>> parseAttrPattern(String keyPattern, @Nullable String exportKey) {
        final String[] components = keyPattern.split(":");
        if (components.length < 2 || components.length > 3) {
            if (exportKey == null) {
                throw new IllegalArgumentException(
                        "invalid attribute export: " + keyPattern +
                        " (expected: attrs.<alias>:<AttributeKey.name>[:<FQCN of Function<?, String>>])");
            } else {
                throw new IllegalArgumentException(
                        "invalid attribute export: " + keyPattern +
                        " (expected: <alias>=attr:<AttributeKey.name>[:<FQCN of Function<?, String>>])");
            }
        }

        if (exportKey == null) {
            exportKey = components[0].substring(PREFIX_ATTRS.length());
        }
        final AttributeKey<Object> attributeKey = AttributeKey.valueOf(components[1]);
        if (components.length == 3) {
            return new ExportEntry<>(attributeKey, exportKey, newStringifier(keyPattern, components[2]));
        } else {
            return new ExportEntry<>(attributeKey, exportKey);
        }
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
