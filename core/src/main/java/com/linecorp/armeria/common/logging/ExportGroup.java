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

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.linecorp.armeria.common.logging.ExportGroupBuilder.ExportEntry;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

/**
 * Holds a set of {@link ExportEntry}s.
 *
 * @see RequestContextExporterBuilder
 */
public final class ExportGroup {

    private final Set<ExportEntry<BuiltInProperty>> builtIns;
    private final Set<ExportEntry<AttributeKey<?>>> attrs;
    private final Set<ExportEntry<AsciiString>> reqHeaders;
    private final Set<ExportEntry<AsciiString>> resHeaders;

    ExportGroup(Set<ExportEntry<BuiltInProperty>> builtIns,
                Set<ExportEntry<AttributeKey<?>>> attrs,
                Set<ExportEntry<AsciiString>> reqHeaders,
                Set<ExportEntry<AsciiString>> resHeaders) {
        this.builtIns = requireNonNull(builtIns, "builtIns");
        this.attrs = requireNonNull(attrs, "attrs");
        this.reqHeaders = requireNonNull(reqHeaders, "reqHeaders");
        this.resHeaders = requireNonNull(resHeaders, "resHeaders");
    }

    /**
     * Returns a new {@link ExportGroupBuilder}.
     */
    public static ExportGroupBuilder builder() {
        return new ExportGroupBuilder();
    }

    /**
     * Returns a set of {@link ExportEntry} of {@link BuiltInProperty}.
     */
    public Set<ExportEntry<BuiltInProperty>> builtIns() {
        return builtIns;
    }

    /**
     * Returns a set of {@link ExportEntry} of {@link AttributeKey}.
     */
    public Set<ExportEntry<AttributeKey<?>>> attrs() {
        return attrs;
    }

    /**
     * Returns a set of {@link ExportEntry} of request headers.
     */
    public Set<ExportEntry<AsciiString>> reqHeaders() {
        return reqHeaders;
    }

    /**
     * Returns a set of {@link ExportEntry} of response headers.
     */
    public Set<ExportEntry<AsciiString>> resHeaders() {
        return resHeaders;
    }
}
