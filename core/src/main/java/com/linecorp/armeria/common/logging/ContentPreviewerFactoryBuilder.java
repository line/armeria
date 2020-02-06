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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeSet;
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

    private final Builder<PreviewSpec> previewSpecsBuilder = ImmutableList.builder();
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
     * Sets the specified {@link MediaTypeSet} to produce the text preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} {@linkplain MediaTypeSet#match(MediaType) matches}
     * the {@link MediaTypeSet}.
     */
    public ContentPreviewerFactoryBuilder text(MediaTypeSet mediaTypeSet) {
        text0(previewerPredicate(mediaTypeSet), null);
        return this;
    }

    /**
     * Sets the specified {@link MediaTypeSet} to produce the text preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} {@linkplain MediaTypeSet#match(MediaType) matches}
     * the {@link MediaTypeSet}.
     *
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public ContentPreviewerFactoryBuilder text(MediaTypeSet mediaTypeSet, Charset defaultCharset) {
        return text(previewerPredicate(mediaTypeSet), defaultCharset);
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the text preview when the predicate
     * returns {@code true}.
     */
    public ContentPreviewerFactoryBuilder text(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        return text0(predicate, null);
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the text preview when the predicate
     * returns {@code true}.
     *
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public ContentPreviewerFactoryBuilder text(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate, Charset defaultCharset) {
        requireNonNull(defaultCharset, "defaultCharset");
        return text0(predicate, defaultCharset);
    }

    private ContentPreviewerFactoryBuilder text0(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate,
            @Nullable Charset defaultCharset) {
        requireNonNull(predicate, "predicate");
        previewSpecsBuilder.add(new PreviewSpec(predicate, PreviewMode.TEXT, defaultCharset, null));
        return this;
    }

    /**
     * Sets the specified {@link MediaTypeSet} to produce the
     * <a href="http://en.wikipedia.org/wiki/Hex_dump">hex dump</a> preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} {@linkplain MediaTypeSet#match(MediaType) matches}
     * the {@link MediaTypeSet}.
     */
    public ContentPreviewerFactoryBuilder binary(MediaTypeSet mediaTypeSet) {
        binary(mediaTypeSet, hexDumpProducer());
        return this;
    }

    /**
     * Sets the specified {@link MediaTypeSet} to produce the preview using the specified {@link BiFunction}
     * when the content type of the {@link RequestHeaders} or {@link ResponseHeaders}
     * {@linkplain MediaTypeSet#match(MediaType) matches} the {@link MediaTypeSet}.
     */
    public ContentPreviewerFactoryBuilder binary(
            MediaTypeSet mediaTypeSet, BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer) {
        requireNonNull(mediaTypeSet, "mediaTypesSet");
        return binary(previewerPredicate(mediaTypeSet), producer);
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the
     * <a href="http://en.wikipedia.org/wiki/Hex_dump">hex dump</a> preview when the predicate
     * returns {@code true}.
     */
    public ContentPreviewerFactoryBuilder binary(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate) {
        return binary(predicate, hexDumpProducer());
    }

    /**
     * Sets the specified {@link BiPredicate} to produce the preview using the specified
     * {@link BiFunction} when the predicate returns {@code true}.
     */
    public ContentPreviewerFactoryBuilder binary(
            BiPredicate<? super RequestContext, ? super HttpHeaders> predicate,
            BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer) {
        requireNonNull(predicate, "predicate");
        requireNonNull(producer, "producer");
        previewSpecsBuilder.add(new PreviewSpec(predicate, PreviewMode.BINARY, null, producer));
        return this;
    }

    /**
     * Sets the specified {@link MediaTypeSet} <b>NOT</b> to produce the preview when the content type of the
     * {@link RequestHeaders} or {@link ResponseHeaders} {@linkplain MediaTypeSet#match(MediaType) matches}
     * the {@link MediaTypeSet}.
     */
    public ContentPreviewerFactoryBuilder disable(MediaTypeSet mediaTypeSet) {
        requireNonNull(mediaTypeSet, "mediaTypesSet");
        return disable(previewerPredicate(mediaTypeSet));
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
            MediaTypeSet mediaTypeSet) {
        requireNonNull(mediaTypeSet, "mediaTypesSet");
        return (ctx, headers) -> {
            final MediaType contentType = headers.contentType();
            if (contentType == null) {
                return false;
            }
            return mediaTypeSet.match(contentType) != null;
        };
    }

    static BiFunction<? super HttpHeaders, ? super ByteBuf, String> hexDumpProducer() {
        return (headers, byteBuf) -> ByteBufUtil.hexDump(byteBuf);
    }
}
