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
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

/**
 * A skeletal builder class which helps easier implementation of an {@link HttpFile} builder.
 *
 * @param <B> the type of {@code this}
 */
public abstract class AbstractHttpFileBuilder<B extends AbstractHttpFileBuilder<B>> {

    private Clock clock = Clock.systemUTC();
    private boolean dateEnabled = true;
    private boolean lastModifiedEnabled = true;
    private boolean contentTypeAutoDetectionEnabled = true;
    @Nullable
    private BiFunction<String, HttpFileAttributes, String> entityTagFunction = DefaultEntityTagFunction.get();
    @Nullable
    private HttpHeaders headers;

    /**
     * Returns {@code this}.
     */
    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

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
    public final B clock(Clock clock) {
        this.clock = requireNonNull(clock, "clock");
        return self();
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
    public final B date(boolean dateEnabled) {
        this.dateEnabled = dateEnabled;
        return self();
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
    public final B lastModified(boolean lastModifiedEnabled) {
        this.lastModifiedEnabled = lastModifiedEnabled;
        return self();
    }

    /**
     * Sets whether to set the {@code "content-type"} header automatically based on the extension of the file.
     */
    protected final boolean isContentTypeAutoDetectionEnabled() {
        return contentTypeAutoDetectionEnabled;
    }

    /**
     * Sets whether to set the {@code "content-type"} header automatically based on the extension of the file.
     * By default, the {@code "content-type"} header is set automatically.
     */
    public final B autoDetectedContentType(boolean contentTypeAutoDetectionEnabled) {
        this.contentTypeAutoDetectionEnabled = contentTypeAutoDetectionEnabled;
        return self();
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
    public final B entityTag(boolean enabled) {
        entityTagFunction = enabled ? DefaultEntityTagFunction.get() : null;
        return self();
    }

    /**
     * Sets the function which generates the entity tag that's used for setting the {@code "etag"} header
     * automatically.
     *
     * @param entityTagFunction the entity tag function that generates the entity tag, or {@code null}
     *                          to disable setting the {@code "etag"} header.
     */
    public final B entityTag(BiFunction<String, HttpFileAttributes, String> entityTagFunction) {
        this.entityTagFunction = requireNonNull(entityTagFunction, "entityTagFunction");
        return self();
    }

    /**
     * Returns the immutable additional {@link HttpHeaders} which will be set when building an
     * {@link HttpResponse}.
     */
    protected final HttpHeaders headers() {
        return headers != null ? headers.asImmutable() : HttpHeaders.EMPTY_HEADERS;
    }

    private HttpHeaders getOrCreateHeaders() {
        if (headers == null) {
            headers = HttpHeaders.of();
        }
        return headers;
    }

    /**
     * Adds the specified HTTP header.
     */
    public final B addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        getOrCreateHeaders().addObject(HttpHeaderNames.of(name), value);
        return self();
    }

    /**
     * Adds the specified HTTP headers.
     */
    public final B addHeaders(Headers<AsciiString, String, ?> headers) {
        requireNonNull(headers, "headers");
        getOrCreateHeaders().add(headers);
        return self();
    }

    /**
     * Sets the specified HTTP header.
     */
    public final B setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        getOrCreateHeaders().setObject(HttpHeaderNames.of(name), value);
        return self();
    }

    /**
     * Sets the specified HTTP headers.
     */
    public final B setHeaders(Headers<AsciiString, String, ?> headers) {
        requireNonNull(headers, "headers");
        getOrCreateHeaders().setAll(headers);
        return self();
    }
}
