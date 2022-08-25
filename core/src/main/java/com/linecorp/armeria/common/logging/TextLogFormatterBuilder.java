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
 * A builder implementation for {@link TextLogFormatter}
 */
@UnstableApi
public final class TextLogFormatterBuilder extends AbstractLogFormatterBuilder<String> {

    private static final BiFunction<RequestContext, HttpHeaders, String> DEFAULT_HEADERS_SANITIZER =
            DEFAULT_SANITIZER();
    private static final BiFunction<RequestContext, Object, String> DEFAULT_CONTENT_SANITIZER =
            DEFAULT_SANITIZER();
    private static final BiFunction<RequestContext, Throwable, String> DEFAULT_CAUSE_SANITIZER =
            DEFAULT_SANITIZER();

    private static <T, U> BiFunction<T, U, String> DEFAULT_SANITIZER() {
        return (first, second) -> second.toString();
    }

    TextLogFormatterBuilder() {}

    /**
     * Sets the {@link BiFunction} to use to sanitize request headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will
     * not sanitize request headers.
     */
    @Override
    public TextLogFormatterBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> requestHeadersSanitizer) {
        return (TextLogFormatterBuilder) super.requestHeadersSanitizer(requestHeadersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will not sanitize response headers.
     */
    @Override
    public TextLogFormatterBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> responseHeadersSanitizer) {
        return (TextLogFormatterBuilder) super.responseHeadersSanitizer(responseHeadersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request trailers before logging. If unset,
     * will not sanitize request trailers.
     */
    @Override
    public TextLogFormatterBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> requestTrailersSanitizer) {
        return (TextLogFormatterBuilder) super.requestTrailersSanitizer(requestTrailersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response trailers before logging. If unset,
     * will not sanitize response trailers.
     */
    @Override
    public TextLogFormatterBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> responseTrailersSanitizer) {
        return (TextLogFormatterBuilder) super.responseTrailersSanitizer(responseTrailersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request, response and trailers before logging.
     * It is common to have the {@link BiFunction} that removes sensitive headers, like {@code "Cookie"} and
     * {@code "Set-Cookie"}, before logging. This method is a shortcut for:
     * <pre>{@code
     * builder.requestHeadersSanitizer(headersSanitizer);
     * builder.requestTrailersSanitizer(headersSanitizer);
     * builder.responseHeadersSanitizer(headersSanitizer);
     * builder.responseTrailersSanitizer(headersSanitizer);
     * }</pre>
     *
     * @see #requestHeadersSanitizer(BiFunction)
     * @see #requestTrailersSanitizer(BiFunction)
     * @see #responseHeadersSanitizer(BiFunction)
     * @see #responseTrailersSanitizer(BiFunction)
     */
    @Override
    public TextLogFormatterBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> headersSanitizer) {
        return (TextLogFormatterBuilder) super.headersSanitizer(headersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an GPS location query, before logging.
     * If unset, will not sanitize request content.
     */
    @Override
    public TextLogFormatterBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends String> requestContentSanitizer) {
        return (TextLogFormatterBuilder) super.requestContentSanitizer(requestContentSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset,
     * will not sanitize response content.
     */
    @Override
    public TextLogFormatterBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends String> responseContentSanitizer) {
        return (TextLogFormatterBuilder) super.responseContentSanitizer(responseContentSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request and response content before logging. It is common
     * to have the {@link BiFunction} that removes sensitive content, such as an GPS location query and
     * an address, before logging. If unset, will not sanitize content.
     * This method is a shortcut for:
     * <pre>{@code
     * builder.requestContentSanitizer(contentSanitizer);
     * builder.responseContentSanitizer(contentSanitizer);
     * }</pre>
     *
     * @see #requestContentSanitizer(BiFunction)
     * @see #responseContentSanitizer(BiFunction)
     */
    @Override
    public TextLogFormatterBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends String> contentSanitizer) {
        return (TextLogFormatterBuilder) super.contentSanitizer(contentSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize a response cause before logging. You can
     * sanitize the stack trace of the exception to remove sensitive information, or prevent from logging
     * the stack trace completely by returning {@code null} in the {@link BiFunction}. If unset, will not
     * sanitize a response cause.
     */
    @Override
    public TextLogFormatterBuilder responseCauseSanitizer(
            BiFunction<? super RequestContext, ? super Throwable, ? extends String> responseCauseSanitizer) {
        return (TextLogFormatterBuilder) super.responseCauseSanitizer(responseCauseSanitizer);
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
                responseContentSanitizer() != null ? responseContentSanitizer() : DEFAULT_CONTENT_SANITIZER,
                responseCauseSanitizer() != null ? responseCauseSanitizer() : DEFAULT_CAUSE_SANITIZER);
    }
}
