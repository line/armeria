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

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import io.netty.buffer.ByteBufAllocator;

/**
 * A skeletal {@link HttpFile} implementation.
 */
public abstract class AbstractHttpFile implements HttpFile {

    @Nullable
    private final MediaType contentType;
    private final boolean dateEnabled;
    private final boolean lastModifiedEnabled;
    @Nullable
    private final BiFunction<String, HttpFileAttributes, String> entityTagFunction;
    private final HttpHeaders headers;

    /**
     * Creates a new instance.
     *
     * @param contentType the {@link MediaType} of the file which will be used as the {@code "content-type"}
     *                    header value. {@code null} to disable setting the {@code "content-type"} header.
     * @param dateEnabled whether to set the {@code "date"} header automatically
     * @param lastModifiedEnabled whether to add the {@code "last-modified"} header automatically
     * @param entityTagFunction the {@link BiFunction} that generates an entity tag from the file's attributes.
     *                          {@code null} to disable setting the {@code "etag"} header.
     * @param headers the additional headers to set
     */
    protected AbstractHttpFile(@Nullable MediaType contentType,
                               boolean dateEnabled,
                               boolean lastModifiedEnabled,
                               @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                               HttpHeaders headers) {

        this.dateEnabled = dateEnabled;
        this.lastModifiedEnabled = lastModifiedEnabled;
        this.contentType = contentType;
        this.entityTagFunction = entityTagFunction;
        this.headers = requireNonNull(headers, "headers").asImmutable();
    }

    /**
     * Returns the {@link String} representation of the file path or URI, which is given to the
     * {@code entityTagFunction} specified in
     * {@linkplain #AbstractHttpFile(MediaType, boolean, boolean, BiFunction, HttpHeaders) the constructor}.
     */
    protected abstract String pathOrUri();

    /**
     * Returns whether to add the {@code "date"} header automatically.
     */
    protected final boolean isDateEnabled() {
        return dateEnabled;
    }

    /**
     * Returns whether to add the {@code "last-modified"} header automatically.
     */
    protected final boolean isLastModifiedEnabled() {
        return lastModifiedEnabled;
    }

    /**
     * Returns the {@link MediaType} of the file, which will be used for setting the {@code "content-type"}
     * header.
     *
     * @return the {@link MediaType} of the file, or {@code null} if the {@code "content-type"} header will not
     *         be set automatically.
     */
    @Nullable
    protected final MediaType contentType() {
        return contentType;
    }

    /**
     * Returns the immutable additional {@link HttpHeaders} which will be set when building an
     * {@link HttpResponse}.
     */
    protected final HttpHeaders headers() {
        return headers;
    }

    /**
     * Generates an entity tag of the file with the given attributes using the {@code entityTagFunction}
     * which was specified with
     * {@linkplain #AbstractHttpFile(MediaType, boolean, boolean, BiFunction, HttpHeaders) the constructor}.
     *
     * @return the entity tag or {@code null} if {@code entityTagFunction} is {@code null}.
     */
    @Nullable
    protected final String generateEntityTag(HttpFileAttributes attrs) {
        requireNonNull(attrs, "attrs");
        return entityTagFunction != null ? entityTagFunction.apply(pathOrUri(), attrs) : null;
    }

    @Nullable
    @Override
    public HttpHeaders readHeaders() throws IOException {
        return readHeaders(readAttributes());
    }

    @Nullable
    private HttpHeaders readHeaders(@Nullable HttpFileAttributes attrs) throws IOException {
        if (attrs == null) {
            return null;
        }

        // TODO(trustin): Cache the headers (sans the'date' header') if attrs did not change.
        final long length = attrs.length();
        final String etag = generateEntityTag(attrs);

        final HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.STATUS, HttpStatus.OK.codeAsText(),
                                                   HttpHeaderNames.CONTENT_LENGTH, Long.toString(length));
        if (contentType != null) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
        }
        if (dateEnabled) {
            headers.setTimeMillis(HttpHeaderNames.DATE, System.currentTimeMillis());
        }
        if (lastModifiedEnabled) {
            headers.setTimeMillis(HttpHeaderNames.LAST_MODIFIED, attrs.lastModifiedMillis());
        }
        if (etag != null) {
            headers.set(HttpHeaderNames.ETAG, '\"' + etag + '\"');
        }
        if (!this.headers.isEmpty()) {
            headers.setAll(this.headers);
        }
        return headers;
    }

    @Override
    public final HttpResponse read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        try {
            final HttpFileAttributes attrs = readAttributes();
            final HttpHeaders headers = readHeaders(attrs);
            if (headers == null) {
                return null;
            }

            final long length = attrs.length();
            if (length == 0) {
                // No need to stream an empty file.
                return HttpResponse.of(headers);
            }

            return doRead(headers, length, fileReadExecutor, alloc);
        } catch (Exception e) {
            return HttpResponse.ofFailure(e);
        }
    }

    /**
     * Returns a new {@link HttpResponse} which streams the content of the file which follows the specified
     * {@link HttpHeaders}.
     *
     * @param headers the {@link HttpHeaders} of the response
     * @param length the content length. The returned {@link HttpResponse} must stream only as many bytes as
     *               this value.
     * @param fileReadExecutor the {@link Executor} which should be used for performing a blocking file I/O
     * @param alloc the {@link ByteBufAllocator} which should be used for allocating an input buffer
     *
     * @return the {@link HttpResponse}, or {@code null} if the file does not exist.
     * @throws IOException if failed to open the file. Note that an I/O error which occurred during content
     *                     streaming will be notified via the returned {@link HttpResponse}'s error
     *                     notification mechanism.
     */
    @Nullable
    protected abstract HttpResponse doRead(HttpHeaders headers, long length,
                                           Executor fileReadExecutor,
                                           ByteBufAllocator alloc) throws IOException;
}
