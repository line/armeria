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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;

/**
 * A factory creating a {@link ContentPreviewer}.
 */
@FunctionalInterface
public interface ContentPreviewerFactory {

    /**
     * The default HTTP content-type charset.
     * See https://tools.ietf.org/html/rfc2616#section-3.7.1
     */
    static Charset defaultCharset() {
        return ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET;
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer} when the
     * content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the specified
     * {@code contentTypes}. {@link ContentPreviewer#disabled()} otherwise.
     *
     * @param function the {@link Function} to create a {@link ContentPreviewer}. The {@link Charset} which
     *                 is the argument of the {@link Function} will be retrieved from
     *                 {@link MediaType#charset()} or {@link #defaultCharset()} if the charset parameter is
     *                 not specified in the {@code "content-type"} header.
     * @param contentTypes the content types
     */
    static ContentPreviewerFactory of(Function<? super Charset, ? extends ContentPreviewer> function,
                                      MediaType... contentTypes) {
        return of(function, ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes")));
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer} when the
     * content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the specified
     * {@code contentTypes}. {@link ContentPreviewer#disabled()} otherwise.
     *
     * @param function the {@link Function} to create a {@link ContentPreviewer}. The {@link Charset} which
     *                 is the argument of the {@link Function} will be retrieved from
     *                 {@link MediaType#charset()} or {@link #defaultCharset()} if the charset parameter is
     *                 not specified in the {@code "content-type"} header.
     * @param contentTypes the content types
     */
    static ContentPreviewerFactory of(Function<? super Charset, ? extends ContentPreviewer> function,
                                      String... contentTypes) {
        return of(function, Arrays.stream(requireNonNull(contentTypes, "contentTypes"))
                                  .map(MediaType::parse)
                                  .collect(toImmutableList()));
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer} when the
     * content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the specified
     * {@code contentTypes}. {@link ContentPreviewer#disabled()} otherwise.
     *
     * @param function the {@link Function} to create a {@link ContentPreviewer}. The {@link Charset} which
     *                 is the argument of the {@link Function} will be retrieved from
     *                 {@link MediaType#charset()} or {@link #defaultCharset()} if the charset parameter is
     *                 not specified in the {@code "content-type"} header.
     * @param contentTypes the content types
     */
    static ContentPreviewerFactory of(Function<? super Charset, ? extends ContentPreviewer> function,
                                      Iterable<MediaType> contentTypes) {
        requireNonNull(function, "function");
        final List<MediaType> candidates = ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes"));
        checkArgument(!candidates.isEmpty(), "contentTypes is empty.");
        candidates.forEach(
                contentType -> checkArgument(contentType != null, "contentType should not be null."));
        return (ctx, headers) -> {
            final MediaType contentType = headers.contentType();
            if (contentType == null) {
                return ContentPreviewer.disabled();
            }
            for (MediaType candidate : candidates) {
                if (contentType.is(candidate)) {
                    return function.apply(contentType.charset(defaultCharset()));
                }
            }

            return ContentPreviewer.disabled();
        };
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer} when the
     * content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link Map#keySet()}
     * in the {@link Map}. The corresponding value {@link Function} will be used to create a
     * {@link ContentPreviewer}. The {@link Charset} which is the argument of the {@link Function} will be
     * retrieved from {@link MediaType#charset()} or {@link #defaultCharset()} if the charset parameter is
     * not specified in the {@code "content-type"} header.
     */
    static ContentPreviewerFactory of(
            Map<MediaType, Function<? super Charset, ? extends ContentPreviewer>> map) {
        return new MappedContentPreviewerFactory(map);
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}.
     * The {@link ContentPreviewer} produces the text with the {@code maxLength} limit
     * if the content type of the {@link RequestHeaders} or {@link ResponseHeaders} meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     *
     * <p>Note that {@link #defaultCharset()} is used when a charset is not specified in the
     * {@code "content-type"} header.
     *
     * @param maxLength the maximum length of the preview
     */
    static ContentPreviewerFactory ofText(int maxLength) {
        return ofText(maxLength, defaultCharset());
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}.
     * The {@link ContentPreviewer} produces the text with the {@code maxLength} limit
     * if the content type of the {@link RequestHeaders} or {@link ResponseHeaders} meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     *
     * @param maxLength the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    static ContentPreviewerFactory ofText(int maxLength, Charset defaultCharset) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        requireNonNull(defaultCharset, "defaultCharset");

        return new TextualContentPreviewerFactory(charset -> ContentPreviewer.ofText(maxLength, charset),
                                                  defaultCharset);
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}.
     * The {@link ContentPreviewer} produces the text with the {@code maxLength} limit
     * if the content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the specified
     * {@code contentTypes}.
     *
     * @param maxLength the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     * @param contentTypes the content types
     */
    static ContentPreviewerFactory ofText(int maxLength, Charset defaultCharset, MediaType... contentTypes) {
        return ofText(maxLength, defaultCharset,
                      ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes")));
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}.
     * The {@link ContentPreviewer} produces the text with the {@code maxLength} limit
     * if the content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the specified
     * {@code contentTypes}.
     *
     * @param maxLength the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     * @param contentTypes the content types
     */
    static ContentPreviewerFactory ofText(int maxLength, Charset defaultCharset, String... contentTypes) {
        return ofText(maxLength, defaultCharset, Arrays.stream(requireNonNull(contentTypes, "contentTypes"))
                                                    .map(MediaType::parse)
                                                    .collect(toImmutableList()));
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}.
     * The {@link ContentPreviewer} produces the text with the {@code maxLength} limit
     * if the content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the specified
     * {@code contentTypes}.
     *
     * @param maxLength the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     * @param contentTypes the content types
     */
    static ContentPreviewerFactory ofText(int maxLength, Charset defaultCharset,
                                          Iterable<MediaType> contentTypes) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        requireNonNull(defaultCharset, "defaultCharset");
        final List<MediaType> candidates = ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes"));
        checkArgument(!candidates.isEmpty(), "contentTypes is empty.");
        candidates.forEach(
                contentType -> checkArgument(contentType != null, "contentType should not be null."));

        return (ctx, headers) -> {
            final MediaType contentType = headers.contentType();
            if (contentType != null) {
                for (MediaType candidate : candidates) {
                    if (contentType.is(candidate)) {
                        return ContentPreviewer.ofText(maxLength, contentType.charset(defaultCharset));
                    }
                }
            }
            return ContentPreviewer.disabled();
        };
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer} using the
     * specified {@link Function}.
     *
     * @param maxLength the maximum length of the preview
     * @param producer the {@link Function} to produce the preview
     */
    static ContentPreviewerFactory ofBinary(int maxLength, Function<? super ByteBuf, String> producer) {
        requireNonNull(producer, "producer");
        return ofBinary(maxLength, (headers, byteBuf) -> producer.apply(byteBuf));
    }

    /**
     * Returns a new {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer} using the
     * specified {@link Function}.
     *
     * @param maxLength the maximum length of the preview
     * @param producer the {@link Function} to produce the preview
     */
    static ContentPreviewerFactory ofBinary(int maxLength,
                                            BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        requireNonNull(producer, "producer");
        return (ctx, headers) -> ContentPreviewer.ofBinary(maxLength, producer, headers);
    }

    /**
     * Returns a newly-created {@link ContentPreviewer} with the given {@link RequestContext} and
     * {@link HttpHeaders}. Note that the returned {@link ContentPreviewer} can be
     * {@link ContentPreviewer#disabled()}.
     */
    ContentPreviewer get(RequestContext ctx, HttpHeaders headers);
}
