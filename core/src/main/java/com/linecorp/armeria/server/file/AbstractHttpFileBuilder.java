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
package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.CacheControl;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal builder class which helps easier implementation of {@link HttpFileBuilder} or
 * {@link AggregatedHttpFileBuilder}.
 */
public abstract class AbstractHttpFileBuilder {

    private Clock clock = Clock.systemUTC();
    private boolean dateEnabled = true;
    private boolean lastModifiedEnabled = true;
    private boolean contentTypeAutoDetectionEnabled = true;
    @Nullable
    private BiFunction<String, HttpFileAttributes, String> entityTagFunction = DefaultEntityTagFunction.get();
    @Nullable
    private HttpHeadersBuilder headers;

    /**
     * Returns the {@link Clock} that provides the current date and time.
     */
    protected final Clock clock() {
        return clock;
    }

    /**
     * Sets the {@link Clock} that provides the current date and time. By default, {@link Clock#systemUTC()}
     * is used.
     */
    public AbstractHttpFileBuilder clock(Clock clock) {
        this.clock = requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Returns whether to set the {@code "date"} header automatically.
     */
    protected final boolean isDateEnabled() {
        return dateEnabled;
    }

    /**
     * Sets whether to set the {@code "date"} header automatically. By default, the {@code "date"} header is
     * set automatically.
     */
    public AbstractHttpFileBuilder date(boolean dateEnabled) {
        this.dateEnabled = dateEnabled;
        return this;
    }

    /**
     * Returns whether to set the {@code "last-modified"} header automatically.
     */
    protected final boolean isLastModifiedEnabled() {
        return lastModifiedEnabled;
    }

    /**
     * Sets whether to set the {@code "last-modified"} header automatically. By default,
     * the {@code "last-modified"} is set automatically.
     */
    public AbstractHttpFileBuilder lastModified(boolean lastModifiedEnabled) {
        this.lastModifiedEnabled = lastModifiedEnabled;
        return this;
    }

    /**
     * Sets whether to set the {@code "content-type"} header automatically based on the extension of the file.
     */
    protected final boolean isContentTypeAutoDetectionEnabled() {
        if (headers != null && headers.contains(HttpHeaderNames.CONTENT_TYPE)) {
            return false;
        }
        return contentTypeAutoDetectionEnabled;
    }

    /**
     * Sets whether to set the {@code "content-type"} header automatically based on the extension of the file.
     * By default, the {@code "content-type"} header is set automatically.
     */
    public AbstractHttpFileBuilder autoDetectedContentType(boolean contentTypeAutoDetectionEnabled) {
        this.contentTypeAutoDetectionEnabled = contentTypeAutoDetectionEnabled;
        return this;
    }

    /**
     * Returns the function which generates the entity tag that's used for setting the {@code "etag"} header
     * automatically.
     *
     * @return the etag function, or {@code null} if the {@code "etag"} header is not set automatically.
     */
    @Nullable
    protected final BiFunction<String, HttpFileAttributes, String> entityTagFunction() {
        return entityTagFunction;
    }

    /**
     * Sets whether to set the {@code "etag"} header automatically based on the path and attributes of the
     * file. By default, the {@code "etag"} header is set automatically. Use {@link #entityTag(BiFunction)} to
     * customize how an entity tag is generated.
     */
    public AbstractHttpFileBuilder entityTag(boolean enabled) {
        entityTagFunction = enabled ? DefaultEntityTagFunction.get() : null;
        return this;
    }

    /**
     * Sets the function which generates the entity tag that's used for setting the {@code "etag"} header
     * automatically.
     *
     * @param entityTagFunction the entity tag function that generates the entity tag, or {@code null}
     *                          to disable setting the {@code "etag"} header.
     */
    public AbstractHttpFileBuilder entityTag(BiFunction<String, HttpFileAttributes, String> entityTagFunction) {
        this.entityTagFunction = requireNonNull(entityTagFunction, "entityTagFunction");
        return this;
    }

    /**
     * Returns the immutable additional {@link HttpHeaders} which will be set when building an
     * {@link HttpResponse}.
     */
    protected final HttpHeaders buildHeaders() {
        return headers != null ? headers.removeAndThen(HttpHeaderNames.STATUS).build() : HttpHeaders.of();
    }

    private HttpHeadersBuilder headersBuilder() {
        if (headers == null) {
            headers = HttpHeaders.builder();
        }
        return headers;
    }

    /**
     * Adds the specified HTTP header.
     */
    public AbstractHttpFileBuilder addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder().addObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Adds the specified HTTP headers.
     */
    public AbstractHttpFileBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        headersBuilder().addObject(headers);
        return this;
    }

    /**
     * Sets the specified HTTP header.
     */
    public AbstractHttpFileBuilder setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder().setObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Sets the specified HTTP headers.
     */
    public AbstractHttpFileBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        headersBuilder().setObject(headers);
        return this;
    }

    /**
     * Sets the {@code "content-type"} header. This method is a shortcut for:
     * <pre>{@code
     * builder.autoDetectedContentType(false);
     * builder.setHeader(HttpHeaderNames.CONTENT_TYPE, contentType);
     * }</pre>
     */
    public AbstractHttpFileBuilder contentType(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        autoDetectedContentType(false);
        headersBuilder().contentType(contentType);
        return this;
    }

    /**
     * Sets the {@code "content-type"} header. This method is a shortcut for:
     * <pre>{@code
     * builder.autoDetectedContentType(false);
     * builder.setHeader(HttpHeaderNames.CONTENT_TYPE, contentType);
     * }</pre>
     */
    public AbstractHttpFileBuilder contentType(CharSequence contentType) {
        requireNonNull(contentType, "contentType");
        autoDetectedContentType(false);
        return setHeader(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    /**
     * Sets the {@code "cache-control"} header. This method is a shortcut for:
     * <pre>{@code
     * builder.setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
     * }</pre>
     */
    public AbstractHttpFileBuilder cacheControl(CacheControl cacheControl) {
        requireNonNull(cacheControl, "cacheControl");
        return setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
    }

    /**
     * Sets the {@code "cache-control"} header. This method is a shortcut for:
     * <pre>{@code
     * builder.setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
     * }</pre>
     */
    public AbstractHttpFileBuilder cacheControl(CharSequence cacheControl) {
        requireNonNull(cacheControl, "cacheControl");
        return setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
    }
}
