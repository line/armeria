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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.logback.RequestContextExporter.ExportEntry;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

final class RequestContextExporterBuilder {

    private static final String PREFIX_ATTRS = "attrs.";
    private static final String PREFIX_HTTP_REQ_HEADERS = "req.http_headers.";
    private static final String PREFIX_HTTP_RES_HEADERS = "res.http_headers.";

    private final Set<BuiltInProperty> builtIns = EnumSet.noneOf(BuiltInProperty.class);
    private final Set<ExportEntry<AttributeKey<?>>> attrs = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> httpReqHeaders = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> httpResHeaders = new HashSet<>();

    void addBuiltIn(BuiltInProperty property) {
        builtIns.add(requireNonNull(property, "property"));
    }

    boolean containsBuiltIn(BuiltInProperty property) {
        return builtIns.contains(requireNonNull(property, "property"));
    }

    Set<BuiltInProperty> getBuiltIns() {
        return Collections.unmodifiableSet(builtIns);
    }

    void addAttribute(String alias, AttributeKey<?> attrKey) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        attrs.add(new ExportEntry<>(attrKey, PREFIX_ATTRS + alias, null));
    }

    void addAttribute(String alias, AttributeKey<?> attrKey, Function<?, String> stringifier) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        requireNonNull(stringifier, "stringifier");
        attrs.add(new ExportEntry<>(attrKey, PREFIX_ATTRS + alias, stringifier));
    }

    boolean containsAttribute(AttributeKey<?> key) {
        requireNonNull(key, "key");
        return attrs.stream().anyMatch(e -> e.key.equals(key));
    }

    Map<String, AttributeKey<?>> getAttributes() {
        return Collections.unmodifiableMap(attrs.stream().collect(
                Collectors.toMap(e -> e.mdcKey.substring(PREFIX_ATTRS.length()), e -> e.key)));
    }

    void addHttpRequestHeader(CharSequence name) {
        addHttpHeader(PREFIX_HTTP_REQ_HEADERS, httpReqHeaders, name);
    }

    void addHttpResponseHeader(CharSequence name) {
        addHttpHeader(PREFIX_HTTP_RES_HEADERS, httpResHeaders, name);
    }

    private static void addHttpHeader(
            String mdcKeyPrefix, Set<ExportEntry<AsciiString>> httpHeaders, CharSequence name) {
        final AsciiString key = toHeaderName(name);
        final String value = mdcKeyPrefix + key;
        httpHeaders.add(new ExportEntry<>(key, value, null));
    }

    boolean containsHttpRequestHeader(CharSequence name) {
        return httpReqHeaders.stream().anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    boolean containsHttpResponseHeader(CharSequence name) {
        return httpResHeaders.stream().anyMatch(e -> e.key.contentEqualsIgnoreCase(name));
    }

    private static AsciiString toHeaderName(CharSequence name) {
        return HttpHeaderNames.of(requireNonNull(name, "name").toString());
    }

    Set<AsciiString> getHttpRequestHeaders() {
        return httpReqHeaders.stream().map(e -> e.key).collect(toImmutableSet());
    }

    Set<AsciiString> getHttpResponseHeaders() {
        return httpResHeaders.stream().map(e -> e.key).collect(toImmutableSet());
    }

    void export(String mdcKey) {
        requireNonNull(mdcKey, "mdcKey");

        final Optional<BuiltInProperty> opt = BuiltInProperty.findByMdcKey(mdcKey);
        if (opt.isPresent()) {
            builtIns.add(opt.get());
            return;
        }

        if (mdcKey.startsWith(PREFIX_ATTRS)) {
            final String[] components = mdcKey.split(":");
            switch (components.length) {
                case 2:
                    addAttribute(components[0].substring(PREFIX_ATTRS.length()),
                                 AttributeKey.valueOf(components[1]));
                    break;
                case 3:
                    final Function<?, String> stringifier = newStringifier(mdcKey, components[2]);

                    addAttribute(components[0].substring(PREFIX_ATTRS.length()),
                                 AttributeKey.valueOf(components[1]),
                                 stringifier);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "invalid attribute export: " + mdcKey +
                            " (expected: attrs.<alias>:<AttributeKey.name>[:<FQCN of Function<?, String>>])");
            }
            return;
        }

        if (mdcKey.startsWith(PREFIX_HTTP_REQ_HEADERS)) {
            addHttpRequestHeader(mdcKey.substring(PREFIX_HTTP_REQ_HEADERS.length()));
            return;
        }

        if (mdcKey.startsWith(PREFIX_HTTP_RES_HEADERS)) {
            addHttpResponseHeader(mdcKey.substring(PREFIX_HTTP_RES_HEADERS.length()));
            return;
        }

        throw new IllegalArgumentException("unknown MDC key: " + mdcKey);
    }

    @SuppressWarnings("unchecked")
    private Function<?, String> newStringifier(String mdcKey, String className) {
        final Function<?, String> stringifier;
        try {
            stringifier = (Function<?, String>) Class.forName(
                    className, true, getClass().getClassLoader()).newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to instantiate a stringifier function: " +
                                               mdcKey, e);
        }
        return stringifier;
    }

    RequestContextExporter build() {
        return new RequestContextExporter(builtIns, attrs, httpReqHeaders, httpResHeaders);
    }
}
