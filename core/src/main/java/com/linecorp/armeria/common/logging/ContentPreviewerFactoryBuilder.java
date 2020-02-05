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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;

/**
 * Builds.
 */
public class ContentPreviewerFactoryBuilder {

    /**
     * Returns.
     */
    public static Charset defaultCharset() {
        return ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET;
    }

    @Nullable
    private List<MediaType> requestContentTypes;
    @Nullable
    private List<MediaType> responseContentTypes;
    @Nullable
    private BiFunction<? super RequestHeaders, ? super ByteBuf, String> requestProducer;
    @Nullable
    private BiFunction<? super ResponseHeaders, ? super ByteBuf, String> responseProducer;
    private boolean isRequestTextContentTypeSet;
    private boolean isResponseTextContentTypeSet;
    @Nullable
    private Charset requestDefaultCharset;
    @Nullable
    private Charset responseDefaultCharset;

    private PreviewableRequestPredicate previewableRequestPredicate = (a, b) -> true;
    private PreviewableResponsePredicate previewableResponsePredicate = (a, b, c) -> true;

    private int requestMaxLength = Integer.MAX_VALUE;
    private int responseMaxLength = Integer.MAX_VALUE;

    ContentPreviewerFactoryBuilder() {}

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder previewableRequestPredicate(
            PreviewableRequestPredicate previewableRequestPredicate) {
        this.previewableRequestPredicate =
                requireNonNull(previewableRequestPredicate, "previewableRequestPredicate");
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder previewableResponsePredicate(
            PreviewableResponsePredicate previewableResponsePredicate) {
        this.previewableResponsePredicate =
                requireNonNull(previewableResponsePredicate, "previewableResponsePredicate");
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder maxLength(int maxLength) {
        requestMaxLength(maxLength);
        responseMaxLength(maxLength);
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder requestMaxLength(int maxLength) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        requestMaxLength = maxLength;
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder responseMaxLength(int maxLength) {
        checkArgument(maxLength > 0, "maxLength : %d (expected: > 0)", maxLength);
        responseMaxLength = maxLength;
        return this;
    }

    /**
     * Sets.
     */
    @SuppressWarnings("OverloadMethodsDeclarationOrder")
    public ContentPreviewerFactoryBuilder defaultCharset(Charset defaultCharset) {
        requestDefaultCharset(defaultCharset);
        responseDefaultCharset(defaultCharset);
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder requestDefaultCharset(Charset defaultCharset) {
        requireNonNull(defaultCharset, "defaultCharset");
        requestDefaultCharset = defaultCharset;
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder responseDefaultCharset(Charset defaultCharset) {
        requireNonNull(defaultCharset, "defaultCharset");
        responseDefaultCharset = defaultCharset;
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder textContentType() {
        requestTextContentType();
        responseTextContentType();
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder requestTextContentType() {
        checkState(requestContentTypes == null,
                   "requestTextContentType() and requestContentTypes() are mutually exclusive.");
        checkState(requestProducer == null,
                   "requestTextContentType() and requestProducer() are mutually exclusive.");
        isRequestTextContentTypeSet = true;
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder responseTextContentType() {
        checkState(responseContentTypes == null,
                   "responseTextContentType() and responseContentTypes() are mutually exclusive.");
        checkState(responseProducer == null,
                   "responseTextContentType() and responseProducer() are mutually exclusive.");
        isResponseTextContentTypeSet = true;
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder contentTypes(MediaType... contentTypes) {
        requestContentTypes(contentTypes);
        responseContentTypes(contentTypes);
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder requestContentTypes(MediaType... contentTypes) {
        checkState(!isRequestTextContentTypeSet,
                   "requestTextContentType() and requestContentTypes() are mutually exclusive.");
        this.requestContentTypes =
                ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes"));
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder responseContentTypes(MediaType... contentTypes) {
        checkState(!isResponseTextContentTypeSet,
                   "responseTextContentType() and responseContentTypes() are mutually exclusive.");
        this.responseContentTypes =
                ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes"));
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder producer(
            Function<? super ByteBuf, String> producer) {
        requireNonNull(producer, "producer");
        producer((headers, byteBuf) -> producer.apply(byteBuf));
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder producer(
            BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer) {
        requestProducer(producer);
        responseProducer(producer);
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder requestProducer(
            BiFunction<? super RequestHeaders, ? super ByteBuf, String> producer) {
        checkState(!isRequestTextContentTypeSet,
                   "requestTextContentType() and requestProducer() are mutually exclusive.");
        this.requestProducer = requireNonNull(producer, "producer");
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactoryBuilder responseProducer(
            BiFunction<? super ResponseHeaders, ? super ByteBuf, String> producer) {
        checkState(!isResponseTextContentTypeSet,
                   "responseTextContentType() and responseProducer() are mutually exclusive.");
        this.responseProducer = requireNonNull(producer, "producer");
        return this;
    }

    /**
     * Sets.
     */
    public ContentPreviewerFactory build() {
        final Function<? super RequestHeaders, ? extends ContentPreviewer> requestPreviewerFactory;
        if (isRequestTextContentTypeSet) {
            final Charset charset = charset(requestDefaultCharset);
            requestPreviewerFactory = new TextualContentPreviewerFunction(requestMaxLength, charset);
        } else {
            final BiFunction<? super RequestHeaders, ? super ByteBuf, String> requestProducer =
                    getRequestProducer();

            if (requestContentTypes != null) {
                requestPreviewerFactory =
                        new ContentTypeBasedRequestPreviewerFunction(requestMaxLength, requestContentTypes,
                                                                     requestProducer);
            } else {
                requestPreviewerFactory =
                        headers -> new RequestContentPreviewer(requestMaxLength, headers, requestProducer);
            }
        }

        final Function<? super ResponseHeaders, ? extends ContentPreviewer> responsePreviewerFactory;
        if (isResponseTextContentTypeSet) {
            final Charset charset = charset(responseDefaultCharset);
            responsePreviewerFactory = new TextualContentPreviewerFunction(responseMaxLength, charset);
        } else {
            final BiFunction<? super ResponseHeaders, ? super ByteBuf, String> responseProducer =
                    getResponseProducer();

            if (responseContentTypes != null) {
                responsePreviewerFactory =
                        new ContentTypeBasedResponsePreviewerFunction(responseMaxLength, responseContentTypes,
                                                                      responseProducer);
            } else {
                responsePreviewerFactory =
                        headers -> new ResponseContentPreviewer(responseMaxLength, headers, responseProducer);
            }
        }

        return new DefaultContentPreviewerFactory(previewableRequestPredicate, previewableResponsePredicate,
                                                  requestPreviewerFactory, responsePreviewerFactory);
    }

    private static Charset charset(@Nullable Charset charset) {
        return charset != null ? charset : defaultCharset();
    }

    private BiFunction<? super RequestHeaders, ? super ByteBuf, String> getRequestProducer() {
        if (requestProducer != null) {
            return requestProducer;
        }

        return new DefaultProducer<>(charset(requestDefaultCharset));
    }

    private BiFunction<? super ResponseHeaders, ? super ByteBuf, String> getResponseProducer() {
        if (responseProducer != null) {
            return responseProducer;
        }

        return new DefaultProducer<>(charset(responseDefaultCharset));
    }

    private static class DefaultProducer<T extends HttpHeaders, U extends ByteBuf>
            implements BiFunction<T, U, String> {

        private final Charset defaultCharset;

        DefaultProducer(Charset defaultCharset) {
            this.defaultCharset = defaultCharset;
        }

        @Override
        public String apply(T headers, U byteBuf) {
            final MediaType contentType = headers.contentType();
            final Charset charset;
            if (contentType != null) {
                charset = contentType.charset(defaultCharset);
            } else {
                charset = defaultCharset;
            }

            return byteBuf.toString(byteBuf.readerIndex(), byteBuf.readableBytes(), charset);
        }
    }
}
