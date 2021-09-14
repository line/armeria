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
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import com.google.common.base.Splitter;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;

import io.netty.buffer.ByteBufAllocator;

/**
 * A skeletal {@link HttpFile} implementation.
 */
public abstract class AbstractHttpFile implements HttpFile {

    private static final Splitter etagSplitter = Splitter.on(',').trimResults().omitEmptyStrings();

    @Nullable
    private final MediaType contentType;
    private final Clock clock;
    private final boolean dateEnabled;
    private final boolean lastModifiedEnabled;
    @Nullable
    private final BiFunction<String, HttpFileAttributes, String> entityTagFunction;
    private final HttpHeaders additionalHeaders;

    /**
     * Creates a new instance.
     *
     * @param contentType the {@link MediaType} of the file which will be used as the {@code "content-type"}
     *                    header value. {@code null} to disable setting the {@code "content-type"} header.
     * @param clock the {@link Clock} which provides the current date and time
     * @param dateEnabled whether to set the {@code "date"} header automatically
     * @param lastModifiedEnabled whether to add the {@code "last-modified"} header automatically
     * @param entityTagFunction the {@link BiFunction} that generates an entity tag from the file's attributes.
     *                          {@code null} to disable setting the {@code "etag"} header.
     * @param additionalHeaders the additional headers to set
     */
    protected AbstractHttpFile(@Nullable MediaType contentType,
                               Clock clock,
                               boolean dateEnabled,
                               boolean lastModifiedEnabled,
                               @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                               HttpHeaders additionalHeaders) {

        this.contentType = contentType;
        this.clock = requireNonNull(clock, "clock");
        this.dateEnabled = dateEnabled;
        this.lastModifiedEnabled = lastModifiedEnabled;
        this.entityTagFunction = entityTagFunction;
        this.additionalHeaders = requireNonNull(additionalHeaders, "additionalHeaders");
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
     * Returns the {@link Clock} which provides the current date and time.
     */
    protected final Clock clock() {
        return clock;
    }

    /**
     * Returns the {@link String} representation of the file path or URI, which is given to the
     * {@code entityTagFunction} specified with the constructor.
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
     * Returns the immutable additional {@link HttpHeaders} which will be set when building an
     * {@link HttpResponse}.
     */
    protected final HttpHeaders additionalHeaders() {
        return additionalHeaders;
    }

    /**
     * Generates an entity tag of the file with the given attributes using the {@code entityTagFunction}
     * which was specified with the constructor.
     *
     * @return the entity tag or {@code null} if {@code entityTagFunction} is {@code null}.
     */
    @Nullable
    protected final String generateEntityTag(HttpFileAttributes attrs) {
        requireNonNull(attrs, "attrs");
        return entityTagFunction != null ? entityTagFunction.apply(pathOrUri(), attrs) : null;
    }

    @Override
    public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        return readAttributes(fileReadExecutor).thenApply(this::readHeaders);
    }

    /**
     * Generates the {@link ResponseHeaders} from the specified {@link HttpFileAttributes}.
     *
     * @return the {@link ResponseHeaders}, or {@code null} if {@code attrs} is {@code null}.
     */
    @Nullable
    protected final ResponseHeaders readHeaders(@Nullable HttpFileAttributes attrs) {
        if (attrs == null) {
            return null;
        }

        // TODO(trustin): Cache the headers (sans the 'date' header') if attrs did not change.
        final String etag = generateEntityTag(attrs);
        final ResponseHeadersBuilder headers =
                ResponseHeaders.builder(HttpStatus.OK)
                               .addLong(HttpHeaderNames.CONTENT_LENGTH, attrs.length());
        return addCommonHeaders(headers, attrs, etag);
    }

    private ResponseHeaders addCommonHeaders(ResponseHeadersBuilder headers, HttpFileAttributes attrs,
                                             @Nullable String etag) {
        if (contentType != null) {
            headers.contentType(contentType);
        }
        if (dateEnabled) {
            headers.setTimeMillis(HttpHeaderNames.DATE, clock.millis());
        }
        if (lastModifiedEnabled) {
            headers.setTimeMillis(HttpHeaderNames.LAST_MODIFIED, attrs.lastModifiedMillis());
        }
        if (etag != null) {
            headers.set(HttpHeaderNames.ETAG, '\"' + etag + '\"');
        }

        headers.set(additionalHeaders);
        return headers.build();
    }

    @Override
    public final CompletableFuture<HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        requireNonNull(alloc, "alloc");

        return readAttributes(fileReadExecutor)
                .thenApply(attrs -> read(fileReadExecutor, alloc, attrs))
                .exceptionally(cause -> HttpResponse.ofFailure(Exceptions.peel(cause)));
    }

    @Nullable
    private HttpResponse read(Executor fileReadExecutor, ByteBufAllocator alloc,
                              @Nullable HttpFileAttributes attrs) {
        final ResponseHeaders headers = readHeaders(attrs);
        if (headers == null) {
            return null;
        }

        final long length = attrs.length();
        if (length == 0) {
            // No need to stream an empty file.
            return HttpResponse.of(headers);
        }

        try {
            return doRead(headers, length, fileReadExecutor, alloc);
        } catch (IOException e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    /**
     * Returns a new {@link HttpResponse} which streams the content of the file which follows the specified
     * {@link ResponseHeaders}.
     *
     * @param headers the {@link ResponseHeaders}
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
    protected abstract HttpResponse doRead(ResponseHeaders headers, long length,
                                           Executor fileReadExecutor,
                                           ByteBufAllocator alloc) throws IOException;

    @Override
    public HttpService asService() {
        return (ctx, req) -> {
            final HttpMethod method = ctx.method();
            if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
                return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            }

            return HttpResponse.from(readAttributes(ctx.blockingTaskExecutor()).thenApply(attrs -> {
                if (attrs == null) {
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                }

                // See https://datatracker.ietf.org/doc/html/rfc7232#section-6 for more information
                // about how conditional requests are handled.

                // Handle 'if-none-match' header.
                final RequestHeaders reqHeaders = req.headers();
                final String etag = generateEntityTag(attrs);
                final String ifNoneMatch = reqHeaders.get(HttpHeaderNames.IF_NONE_MATCH);
                if (etag != null && ifNoneMatch != null) {
                    if ("*".equals(ifNoneMatch) || entityTagMatches(etag, ifNoneMatch)) {
                        return newNotModified(attrs, etag);
                    }
                }

                // Handle 'if-modified-since' header, only if 'if-none-match' does not exist.
                if (ifNoneMatch == null) {
                    try {
                        final Long ifModifiedSince =
                                reqHeaders.getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE);
                        if (ifModifiedSince != null) {
                            // HTTP-date does not have subsecond-precision; add 999ms to it.
                            final long ifModifiedSinceMillis = LongMath.saturatedAdd(ifModifiedSince, 999);
                            if (attrs.lastModifiedMillis() <= ifModifiedSinceMillis) {
                                return newNotModified(attrs, etag);
                            }
                        }
                    } catch (Exception ignore) {
                        // Malformed date.
                    }
                }

                // Precondition did not match. Handle as usual.
                switch (ctx.method()) {
                    case HEAD:
                        final ResponseHeaders resHeaders = readHeaders(attrs);
                        if (resHeaders != null) {
                            return HttpResponse.of(resHeaders);
                        }
                        break;
                    case GET:
                        final HttpResponse res = read(ctx.blockingTaskExecutor(), ctx.alloc(), attrs);
                        if (res != null) {
                            return res;
                        }
                        break;
                    default:
                        throw new Error(); // Never reaches here.
                }

                // readHeaders() or read() returned null above.
                return HttpResponse.of(HttpStatus.NOT_FOUND);
            }));
        };
    }

    private static boolean entityTagMatches(String entityTag, String ifNoneMatch) {
        for (String candidate : etagSplitter.split(ifNoneMatch)) {
            final String candidateETag = extractEntityTag(candidate);
            if (entityTag.equals(candidateETag)) {
                return true;
            }
        }

        return false;
    }

    private static String extractEntityTag(String value) {
        int i = 0;
        int etagStart = -1;
        int etagEnd = -1;
        for (; i < value.length(); i++) {
            if (value.charAt(i) == '\"') {
                etagStart = i + 1;
                i++;
                break;
            }
        }

        if (etagStart < 0) {
            // Not surrounded by double quotes.
            return value;
        }

        for (; i < value.length(); i++) {
            if (value.charAt(i) == '\"') {
                etagEnd = i;
                break;
            }
        }

        return etagEnd > 0 ? value.substring(etagStart, etagEnd) : value.substring(etagStart);
    }

    private HttpResponse newNotModified(HttpFileAttributes attrs, @Nullable String etag) {
        return HttpResponse.of(addCommonHeaders(ResponseHeaders.builder(HttpStatus.NOT_MODIFIED),
                                                attrs, etag));
    }
}
