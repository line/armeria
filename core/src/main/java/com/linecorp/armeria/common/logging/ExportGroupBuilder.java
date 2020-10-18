/*
 * Copyright 2020 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;

import com.linecorp.armeria.common.HttpHeaderNames;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

/**
 * Builds a new {@link ExportGroup}.
 */
public final class ExportGroupBuilder {

    private static final String PREFIX_REQ_HEADERS = "req.headers.";
    private static final String PREFIX_RES_HEADERS = "res.headers.";

    static final String PREFIX_ATTRS = "attrs.";
    private static final String ATTR_NAMESPACE = "attr:";

    private static final Splitter KEY_SPLITTER = Splitter.on(',').trimResults();

    @Nullable
    private String prefix;
    private final Set<ExportEntry<BuiltInProperty>> builtIns;
    private final Set<ExportEntry<AttributeKey<?>>> attrs;
    private final Set<ExportEntry<AsciiString>> reqHeaders;
    private final Set<ExportEntry<AsciiString>> resHeaders;

    ExportGroupBuilder() {
        builtIns = new HashSet<>();
        attrs = new HashSet<>();
        reqHeaders = new HashSet<>();
        resHeaders = new HashSet<>();
    }

    /**
     * Builds a new {@link ExportGroup}.
     * If a prefix is specified, returns entries with the prefix.
     */
    public ExportGroup build() {
        if (prefix == null) {
            return new ExportGroup(builtIns, attrs, reqHeaders, resHeaders);
        } else {
            return new ExportGroup(
                    ExportEntry.withPrefix(builtIns, prefix),
                    ExportEntry.withPrefix(attrs, prefix),
                    ExportEntry.withPrefix(reqHeaders, prefix),
                    ExportEntry.withPrefix(resHeaders, prefix));
        }
    }

    /**
     * Specifies a prefix of the default export group.
     */
    public ExportGroupBuilder prefix(String prefix) {
        requireNonNull(prefix, "prefix");
        checkArgument(!prefix.isEmpty(), "prefix must not be empty");
        this.prefix = prefix;
        return this;
    }

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     * The specified {@code alias} will be used for the export key.
     */
    public ExportGroupBuilder builtIn(BuiltInProperty property, String alias) {
        requireNonNull(property, "BuiltInProperty");
        requireNonNull(alias, "alias");
        builtIns.add(new ExportEntry<>(property, alias));
        return this;
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     * The specified {@code alias} is used for the export key.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     */
    public ExportGroupBuilder attr(String alias, AttributeKey<?> attrKey) {
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
    public ExportGroupBuilder attr(String alias, AttributeKey<?> attrKey, Function<?, String> stringifier) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        requireNonNull(stringifier, "stringifier");
        attrs.add(new ExportEntry<>(attrKey, alias, stringifier));
        return this;
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public ExportGroupBuilder requestHeader(CharSequence headerName) {
        requireNonNull(headerName, "headerName");
        final AsciiString key = toHeaderName(headerName);
        reqHeaders.add(new ExportEntry<>(key, PREFIX_REQ_HEADERS + key));
        return this;
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public ExportGroupBuilder requestHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        reqHeaders.add(new ExportEntry<>(toHeaderName(headerName), alias));
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public ExportGroupBuilder responseHeader(CharSequence headerName) {
        requireNonNull(headerName, "headerName");
        final AsciiString key = toHeaderName(headerName);
        resHeaders.add(new ExportEntry<>(key, PREFIX_RES_HEADERS + key));
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public ExportGroupBuilder responseHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        resHeaders.add(new ExportEntry<>(toHeaderName(headerName), alias));
        return this;
    }

    private static AsciiString toHeaderName(CharSequence name) {
        return HttpHeaderNames.of(requireNonNull(name, "name").toString());
    }

    /**
     * Adds the property represented by the specified key pattern to the export list. Please refer to the
     * <a href="https://armeria.dev/docs/advanced-logging">Logging contextual information</a>
     * in order to learn how to specify a key pattern.
     */
    public ExportGroupBuilder keyPattern(String keyPattern) {
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

        if (keyPattern.startsWith(PREFIX_REQ_HEADERS)) {
            if (exportKey == null) {
                requestHeader(keyPattern.substring(PREFIX_REQ_HEADERS.length()));
            } else {
                requestHeader(keyPattern.substring(PREFIX_REQ_HEADERS.length()), exportKey);
            }
            return this;
        }

        if (keyPattern.startsWith(PREFIX_RES_HEADERS)) {
            if (exportKey == null) {
                requestHeader(keyPattern.substring(PREFIX_RES_HEADERS.length()));
            } else {
                requestHeader(keyPattern.substring(PREFIX_RES_HEADERS.length()), exportKey);
            }
            return this;
        }

        throw new IllegalArgumentException("unknown key pattern: " + keyPattern);
    }

    /**
     * Adds the property represented by the specified key pattern to the export list.
     */
    public ExportGroupBuilder keyPatterns(String keyPatterns) {
        KEY_SPLITTER.split(keyPatterns)
                    .forEach(keyPattern -> {
                        checkArgument(!keyPattern.isEmpty(), "comma-separated keyPattern must not be empty");
                        keyPattern(keyPattern);
                    });
        return this;
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
            exportKey = components[0];
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

    static final class ExportEntry<T> {
        final T key;
        final String exportKey;
        @Nullable
        final Function<Object, String> stringifier;

        ExportEntry(T key, String exportKey) {
            requireNonNull(key);
            requireNonNull(exportKey);
            this.key = key;
            this.exportKey = exportKey;
            stringifier = null;
        }

        @SuppressWarnings("unchecked")
        ExportEntry(T key, String exportKey, Function<?, ?> stringifier) {
            requireNonNull(key);
            requireNonNull(exportKey);
            requireNonNull(stringifier);
            this.key = key;
            this.exportKey = exportKey;
            this.stringifier = (Function<Object, String>) stringifier;
        }

        @Nullable
        String stringify(@Nullable Object value) {
            if (stringifier == null) {
                return value != null ? value.toString() : null;
            } else {
                return stringifier.apply(value);
            }
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + exportKey.hashCode();
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof ExportEntry)) {
                return false;
            }

            return key.equals(((ExportEntry<?>) o).key) &&
                   exportKey.equals(((ExportEntry<?>) o).exportKey);
        }

        @Override
        public String toString() {
            return exportKey + ':' + key;
        }

        public ExportEntry<T> withPrefix(String exportPrefix) {
            checkArgument(!exportPrefix.isEmpty(), "exportPrefix must not be empty");

            if (stringifier == null) {
                return new ExportEntry<>(key, exportPrefix + exportKey);
            } else {
                return new ExportEntry<>(key, exportPrefix + exportKey, stringifier);
            }
        }

        public static <T> Set<ExportEntry<T>> withPrefix(Set<ExportEntry<T>> entries, String exportPrefix) {
            checkArgument(!exportPrefix.isEmpty(), "exportPrefix must not be empty");

            return entries.stream().map(entry -> entry.withPrefix(exportPrefix)).collect(Collectors.toSet());
        }
    }
}
