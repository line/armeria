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

import java.nio.charset.Charset;
import java.util.List;
import java.util.function.BiFunction;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;

final class DefaultContentPreviewFactory implements ContentPreviewerFactory {

    private static final List<String> textSubTypes = ImmutableList.of("json", "xml");
    private static final List<String> textSubTypeSuffixes = ImmutableList.of("+json", "+xml");

    private final int maxLength;
    private final List<PreviewSpec> previewSpecs;
    private final Charset defaultCharset;

    DefaultContentPreviewFactory(List<PreviewSpec> previewSpecs, int maxLength, Charset defaultCharset) {
        this.maxLength = maxLength;
        this.previewSpecs = previewSpecs;
        this.defaultCharset = defaultCharset;
    }

    @Override
    public ContentPreviewer requestContentPreviewer(RequestContext ctx, RequestHeaders headers) {
        return contentPreviewer(ctx, headers);
    }

    @Override
    public ContentPreviewer responseContentPreviewer(RequestContext ctx, ResponseHeaders resHeaders) {
        return contentPreviewer(ctx, resHeaders);
    }

    private ContentPreviewer contentPreviewer(RequestContext ctx, HttpHeaders headers) {
        for (PreviewSpec previewSpec : previewSpecs) {
            if (previewSpec.predicate().test(ctx, headers)) {
                switch (previewSpec.mode()) {
                    case TEXT:
                        final Charset charset = charset(headers);
                        return new TextContentPreviewer(maxLength, charset);
                    case BINARY:
                        final BiFunction<? super HttpHeaders, ? super ByteBuf, String> producer =
                                previewSpec.producer();
                        assert producer != null;
                        return new ProducerBasedContentPreviewer(maxLength, headers, producer);
                    case DISABLED:
                        return ContentPreviewer.disabled();
                }
            }
        }

        @Nullable
        final MediaType contentType = headers.contentType();
        if (contentType != null) {
            @Nullable
            final Charset charset = contentType.charset();
            if (charset != null) {
                return new TextContentPreviewer(maxLength, charset);
            }

            if ("text".equals(contentType.type()) ||
                textSubTypes.contains(contentType.subtype()) ||
                textSubTypeSuffixes.stream().anyMatch(contentType.subtype()::endsWith) ||
                contentType.is(MediaType.FORM_DATA)) {
                return new TextContentPreviewer(maxLength, defaultCharset);
            }
        }

        return ContentPreviewer.disabled();
    }

    private Charset charset(HttpHeaders headers) {
        @Nullable
        final MediaType contentType = headers.contentType();
        if (contentType != null) {
            return contentType.charset(defaultCharset);
        } else {
            return defaultCharset;
        }
    }
}
