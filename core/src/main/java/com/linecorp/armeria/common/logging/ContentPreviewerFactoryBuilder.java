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

import java.nio.charset.Charset;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.PreviewSpec.PreviewMode;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * A builder which builds a {@link ContentPreviewerFactory}.
 */
public final class ContentPreviewerFactoryBuilder {

    //TODO(minwoox): Add setters for the seprate request and response previewer.

    private final ImmutableList.Builder<PreviewSpec> previewSpecsBuilder = ImmutableList.builder();
    private int maxLength;
    private Charset defaultCharset = ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET;

    ContentPreviewerFactoryBuilder() {}

    /**
     * Sets the maximum length of the produced preview.
     */
    public ContentPreviewerFactoryBuilder maxLength(int maxLength) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        this.maxLength = maxLength;
        return this;
    }

    /**
     * Sets the default {@link Charset} used to produce the text preview when a charset is not specified in the
     * {@code "content-type"} header.
     *
     * <p>Note that this charset is only for the text preview, not for the binary preview.</p>
     */
    public ContentPreviewerFactoryBuilder defaultCharset(Charset defaultCharset) {
        this.defaultCharset = requireNonNull(defaultCharset, "defaultCharset");
        return this;
    }

    /**
     * Sets the specified {@link MediaType}s to produce the text preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s.
     */
    public ContentPreviewerFactoryBuilder text(MediaType... mediaTypes) {
        return text(ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes")));
    }

    /**
     * Sets the specified {@link MediaType}s to produce the text preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s.
     */
    public ContentPreviewerFactoryBuilder text(Iterable<MediaType> mediaTypes) {
        text0(null, previewerPredicate(mediaTypes));
        return this;
    }

    /**
     * Sets the specified {@link MediaType}s to produce the text preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s.
     *
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public ContentPreviewerFactoryBuilder text(Charset defaultCharset, MediaType... mediaTypes) {
        return text(defaultCharset, ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes")));
    }

    /**
     * Sets the specified {@link MediaType}s to produce the text preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s.
     *
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public ContentPreviewerFactoryBuilder text(Charset defaultCharset, Iterable<MediaType> mediaTypes) {
        return text(defaultCharset, previewerPredicate(mediaTypes));
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the text preview when the predicate
     * returns {@code true}.
     */
    public ContentPreviewerFactoryBuilder text(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        return text0(null, predicate);
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the text preview when the predicate
     * returns {@code true}.
     *
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public ContentPreviewerFactoryBuilder text(
            Charset defaultCharset, BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        requireNonNull(defaultCharset, "defaultCharset");
        return text0(defaultCharset, predicate);
    }

    private ContentPreviewerFactoryBuilder text0(
            @Nullable Charset defaultCharset,
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        requireNonNull(predicate, "predicate");
        previewSpecsBuilder.add(new PreviewSpec(predicate, PreviewMode.TEXT, defaultCharset, null));
        return this;
    }

    /**
     * Sets the specified {@link MediaType}s to produce the
     * <a href="http://en.wikipedia.org/wiki/Hex_dump">hex dump</a> preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s.
     */
    public ContentPreviewerFactoryBuilder binary(MediaType... mediaTypes) {
        return binary(ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes")));
    }

    /**
     * Sets the specified {@link MediaType}s to produce the
     * <a href="http://en.wikipedia.org/wiki/Hex_dump">hex dump</a> preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s.
     */
    public ContentPreviewerFactoryBuilder binary(Iterable<MediaType> mediaTypes) {
        binary(hexDumpProducer(), mediaTypes);
        return this;
    }

    /**
     * Sets the specified {@link MediaType}s to produce the preview using the specified {@link BiFunction}
     * when the content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the
     * {@link MediaType}s.
     */
    public ContentPreviewerFactoryBuilder binary(
            BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer, MediaType... mediaTypes) {
        return binary(producer, ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes")));
    }

    /**
     * Sets the specified {@link MediaType}s to produce the preview using the specified {@link BiFunction}
     * when the content type of the {@link RequestHeaders} or {@link ResponseHeaders} is one of the
     * {@link MediaType}s.
     */
    public ContentPreviewerFactoryBuilder binary(
            BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer, Iterable<MediaType> mediaTypes) {
        return binary(producer,
                      previewerPredicate(ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes"))));
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the
     * <a href="http://en.wikipedia.org/wiki/Hex_dump">hex dump</a> preview when the predicate
     * returns {@code true}.
     */
    public ContentPreviewerFactoryBuilder binary(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        return binary(hexDumpProducer(), predicate);
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the preview using the specified
     * {@link BiFunction} when the predicate returns {@code true}.
     */
    public ContentPreviewerFactoryBuilder binary(
            BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer,
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        requireNonNull(predicate, "predicate");
        requireNonNull(producer, "producer");
        previewSpecsBuilder.add(new PreviewSpec(predicate, PreviewMode.BINARY, null, producer));
        return this;
    }

    /**
     * Sets the specified {@link MediaType}s <b>NOT</b> to produce the preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s
     */
    public ContentPreviewerFactoryBuilder disable(MediaType... mediaTypes) {
        return disable(ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes")));
    }

    /**
     * Sets the specified {@link MediaType}s <b>NOT</b> to produce the preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} is one of the {@link MediaType}s
     */
    public ContentPreviewerFactoryBuilder disable(Iterable<MediaType> mediaTypes) {
        return disable(previewerPredicate(ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes"))));
    }

    /**
     * Sets the specified {@link BiPredicate} <b>NOT</b> to produce the preview when the predicate
     * returns {@code true}.
     */
    public ContentPreviewerFactoryBuilder disable(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        requireNonNull(predicate, "predicate");
        previewSpecsBuilder.add(new PreviewSpec(predicate, PreviewMode.DISABLED, null, null));
        return this;
    }

    /**
     * Returns a newly-created {@link ContentPreviewerFactory} based on the properties of this builder.
     */
    public ContentPreviewerFactory build() {
        return new DefaultContentPreviewFactory(previewSpecsBuilder.build(), maxLength, defaultCharset);
    }

    private static BiPredicate<? super RequestContext, ? super HttpHeaders> previewerPredicate(
            Iterable<MediaType> meidaTypes) {
        return (ctx, headers) -> {
            final MediaType contentType = headers.contentType();
            if (contentType == null) {
                return false;
            }
            return Streams.stream(meidaTypes).anyMatch(contentType::belongsTo);
        };
    }

    @VisibleForTesting
    static BiFunction<? super HttpHeaders, ? super ByteBuf, String> hexDumpProducer() {
        return (headers, byteBuf) -> ByteBufUtil.hexDump(byteBuf);
    }
}
