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
package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.CacheControl;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;

/**
 * Builds an {@link AggregatedHttpFile} from an {@link HttpData}.
 * <pre>{@code
 * AggregatedHttpFile f =
 *         AggregatedHttpFile.builder(HttpData.ofUtf8("content"), System.currentTimeMillis())
 *                           .entityTag((pathOrUri, attrs) -> "myCustomEntityTag")
 *                           .build();
 * }</pre>
 */
public final class AggregatedHttpFileBuilder extends AbstractHttpFileBuilder {

    private final HttpData content;
    private final long lastModifiedMillis;

    AggregatedHttpFileBuilder(HttpData content, long lastModifiedMillis) {
        this.content = requireNonNull(content, "content");
        this.lastModifiedMillis = lastModifiedMillis;
    }

    /**
     * Returns a newly created {@link AggregatedHttpFile} with the properties configured so far.
     */
    public AggregatedHttpFile build() {
        return new HttpDataFile(content, clock(), lastModifiedMillis,
                                isDateEnabled(), isLastModifiedEnabled(),
                                entityTagFunction(), buildHeaders());
    }

    // Methods from the supertype that are overridden to change the return type.

    @Override
    public AggregatedHttpFileBuilder clock(Clock clock) {
        return (AggregatedHttpFileBuilder) super.clock(clock);
    }

    @Override
    public AggregatedHttpFileBuilder date(boolean dateEnabled) {
        return (AggregatedHttpFileBuilder) super.date(dateEnabled);
    }

    @Override
    public AggregatedHttpFileBuilder lastModified(boolean lastModifiedEnabled) {
        return (AggregatedHttpFileBuilder) super.lastModified(lastModifiedEnabled);
    }

    @Override
    public AggregatedHttpFileBuilder autoDetectedContentType(boolean contentTypeAutoDetectionEnabled) {
        return (AggregatedHttpFileBuilder) super.autoDetectedContentType(contentTypeAutoDetectionEnabled);
    }

    @Override
    public AggregatedHttpFileBuilder entityTag(boolean enabled) {
        return (AggregatedHttpFileBuilder) super.entityTag(enabled);
    }

    @Override
    public AggregatedHttpFileBuilder entityTag(
            BiFunction<String, HttpFileAttributes, String> entityTagFunction) {
        return (AggregatedHttpFileBuilder) super.entityTag(entityTagFunction);
    }

    @Override
    public AggregatedHttpFileBuilder addHeader(CharSequence name, Object value) {
        return (AggregatedHttpFileBuilder) super.addHeader(name, value);
    }

    @Override
    public AggregatedHttpFileBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (AggregatedHttpFileBuilder) super.addHeaders(headers);
    }

    @Override
    public AggregatedHttpFileBuilder setHeader(CharSequence name, Object value) {
        return (AggregatedHttpFileBuilder) super.setHeader(name, value);
    }

    @Override
    public AggregatedHttpFileBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (AggregatedHttpFileBuilder) super.setHeaders(headers);
    }

    @Override
    public AggregatedHttpFileBuilder contentType(MediaType contentType) {
        return (AggregatedHttpFileBuilder) super.contentType(contentType);
    }

    @Override
    public AggregatedHttpFileBuilder contentType(CharSequence contentType) {
        return (AggregatedHttpFileBuilder) super.contentType(contentType);
    }

    @Override
    public AggregatedHttpFileBuilder cacheControl(CacheControl cacheControl) {
        return (AggregatedHttpFileBuilder) super.cacheControl(cacheControl);
    }

    @Override
    public AggregatedHttpFileBuilder cacheControl(CharSequence cacheControl) {
        return (AggregatedHttpFileBuilder) super.cacheControl(cacheControl);
    }
}
