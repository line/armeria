/*
 * Copyright 2022 LINE Corporation
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

import java.util.function.BiFunction;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder implementation for {@link TextLogFormatter}.
 */
@UnstableApi
public final class TextLogFormatterBuilder extends AbstractLogFormatterBuilder<String> {

    private static final BiFunction<RequestContext, HttpHeaders, String> DEFAULT_HEADERS_SANITIZER =
            defaultSanitizer();
    private static final BiFunction<RequestContext, Object, String> DEFAULT_CONTENT_SANITIZER =
            defaultSanitizer();

    private static <T, U> BiFunction<T, U, String> defaultSanitizer() {
        return (first, second) -> second.toString();
    }

    TextLogFormatterBuilder() {}

    @Override
    public TextLogFormatterBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> requestHeadersSanitizer) {
        return (TextLogFormatterBuilder) super.requestHeadersSanitizer(requestHeadersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String>
                    responseHeadersSanitizer) {
        return (TextLogFormatterBuilder) super.responseHeadersSanitizer(responseHeadersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String>
                    requestTrailersSanitizer) {
        return (TextLogFormatterBuilder) super.requestTrailersSanitizer(requestTrailersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String>
                    responseTrailersSanitizer) {
        return (TextLogFormatterBuilder) super.responseTrailersSanitizer(responseTrailersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> headersSanitizer) {
        return (TextLogFormatterBuilder) super.headersSanitizer(headersSanitizer);
    }

    @Override
    public TextLogFormatterBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends String> requestContentSanitizer) {
        return (TextLogFormatterBuilder) super.requestContentSanitizer(requestContentSanitizer);
    }

    @Override
    public TextLogFormatterBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends String> responseContentSanitizer) {
        return (TextLogFormatterBuilder) super.responseContentSanitizer(responseContentSanitizer);
    }

    @Override
    public TextLogFormatterBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends String> contentSanitizer) {
        return (TextLogFormatterBuilder) super.contentSanitizer(contentSanitizer);
    }

    /**
     * Returns a newly-created {@link TextLogFormatter} based on the properties of this builder.
     */
    public TextLogFormatter build() {
        return new TextLogFormatter(
                requestHeadersSanitizer() != null ? requestHeadersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                responseHeadersSanitizer() != null ? responseHeadersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                requestTrailersSanitizer() != null ? requestTrailersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                responseTrailersSanitizer() != null ? responseTrailersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                requestContentSanitizer() != null ? requestContentSanitizer() : DEFAULT_CONTENT_SANITIZER,
                responseContentSanitizer() != null ? responseContentSanitizer() : DEFAULT_CONTENT_SANITIZER);
    }
}
