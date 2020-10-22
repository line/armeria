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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.netty.util.AttributeKey;

/**
 * Builds a new {@link RequestContextExporter}.
 */
public final class RequestContextExporterBuilder {

    private final ExportGroupBuilder defaultExportGroupBuilder = ExportGroup.builder();
    private final List<ExportGroup> exportGroups = new ArrayList<>();

    RequestContextExporterBuilder() {}

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     * The {@link BuiltInProperty#key} will be used for the export key.
     */
    public RequestContextExporterBuilder builtIn(BuiltInProperty property) {
        requireNonNull(property, "property");
        return builtIn(property, property.key);
    }

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     * The specified {@code alias} will be used for the export key.
     */
    public RequestContextExporterBuilder builtIn(BuiltInProperty property, String alias) {
        requireNonNull(property, "property");
        requireNonNull(alias, "alias");
        defaultExportGroupBuilder.builtIn(property, alias);
        return this;
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     * The specified {@code alias} is used for the export key.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     */
    public RequestContextExporterBuilder attr(String alias, AttributeKey<?> attrKey) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        defaultExportGroupBuilder.attr(alias, attrKey);
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
    public RequestContextExporterBuilder attr(String alias, AttributeKey<?> attrKey,
                                              Function<?, String> stringifier) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        requireNonNull(stringifier, "stringifier");
        defaultExportGroupBuilder.attr(alias, attrKey, stringifier);
        return this;
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public RequestContextExporterBuilder requestHeader(CharSequence headerName) {
        requireNonNull(headerName, "headerName");
        defaultExportGroupBuilder.requestHeader(headerName);
        return this;
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public RequestContextExporterBuilder requestHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        defaultExportGroupBuilder.requestHeader(headerName, alias);
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public RequestContextExporterBuilder responseHeader(CharSequence headerName) {
        requireNonNull(headerName, "headerName");
        defaultExportGroupBuilder.responseHeader(headerName);
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public RequestContextExporterBuilder responseHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        defaultExportGroupBuilder.responseHeader(headerName, alias);
        return this;
    }

    /**
     * Specifies a prefix of the default export group.
     */
    public void setDefaultExportPrefix(String defaultExportPrefix) {
        requireNonNull(defaultExportPrefix, "defaultExportPrefix");
        checkArgument(!defaultExportPrefix.isEmpty(), "defaultExportPrefix must not be empty");
        defaultExportGroupBuilder.setPrefix(defaultExportPrefix);
    }

    /**
     * Adds the property represented by the specified key pattern to the export list. Please refer to the
     * <a href="https://armeria.dev/docs/advanced-logging">Logging contextual information</a>
     * in order to learn how to specify a key pattern.
     */
    public RequestContextExporterBuilder keyPattern(String keyPattern) {
        requireNonNull(keyPattern, "keyPattern");
        checkArgument(!keyPattern.isEmpty(), "keyPattern must not be empty");
        defaultExportGroupBuilder.keyPattern(keyPattern);
        return this;
    }

    /**
     * Adds the property represented by the specified key pattern to the export list.
     */
    public RequestContextExporterBuilder keyPatterns(String keyPatterns) {
        requireNonNull(keyPatterns, "keyPatterns");
        checkArgument(!keyPatterns.isEmpty(), "keyPatterns must not be empty");
        defaultExportGroupBuilder.keyPatterns(keyPatterns);
        return this;
    }

    /**
     * Adds the export group.
     */
    public RequestContextExporterBuilder addExportGroup(ExportGroup exportGroup) {
        exportGroups.add(exportGroup);
        return this;
    }

    /**
     * Returns a newly-created {@link RequestContextExporter} instance.
     */
    public RequestContextExporter build() {
        exportGroups.add(defaultExportGroupBuilder.build());
        return new RequestContextExporter(
                exportGroups.stream().flatMap(it -> it.getBuiltIns().stream()).collect(Collectors.toSet()),
                exportGroups.stream().flatMap(it -> it.getAttrs().stream()).collect(Collectors.toSet()),
                exportGroups.stream().flatMap(it -> it.getReqHeaders().stream()).collect(Collectors.toSet()),
                exportGroups.stream().flatMap(it -> it.getResHeaders().stream()).collect(Collectors.toSet())
        );
    }
}
