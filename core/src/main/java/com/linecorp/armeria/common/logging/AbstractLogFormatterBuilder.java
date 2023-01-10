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

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal builder implementation for {@link LogFormatter}.
 */
abstract class AbstractLogFormatterBuilder<T> {

    @Nullable
    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestHeadersSanitizer;

    @Nullable
    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseHeadersSanitizer;

    @Nullable
    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestTrailersSanitizer;

    @Nullable
    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseTrailersSanitizer;

    @Nullable
    private BiFunction<? super RequestContext, Object, ? extends T> requestContentSanitizer;

    @Nullable
    private BiFunction<? super RequestContext, Object, ? extends T> responseContentSanitizer;

    /**
     * Sets the {@link BiFunction} to use to sanitize request headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will
     * not sanitize request headers.
     */
    public AbstractLogFormatterBuilder<T> requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestHeadersSanitizer) {
        this.requestHeadersSanitizer = requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request headers before logging.
     */
    @Nullable
    final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestHeadersSanitizer() {
        return requestHeadersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will not sanitize response headers.
     */
    public AbstractLogFormatterBuilder<T> responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseHeadersSanitizer) {
        this.responseHeadersSanitizer = requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response headers before logging.
     */
    @Nullable
    final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseHeadersSanitizer() {
        return responseHeadersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request trailers before logging. If unset,
     * will not sanitize request trailers.
     */
    public AbstractLogFormatterBuilder<T> requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestTrailersSanitizer) {
        this.requestTrailersSanitizer = requireNonNull(requestTrailersSanitizer, "requestTrailersSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request trailers before logging.
     */
    @Nullable
    final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestTrailersSanitizer() {
        return requestTrailersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response trailers before logging. If unset,
     * will not sanitize response trailers.
     */
    public AbstractLogFormatterBuilder<T> responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseTrailersSanitizer) {
        this.responseTrailersSanitizer = requireNonNull(responseTrailersSanitizer, "responseTrailersSanitizer");
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response trailers before logging.
     */
    @Nullable
    final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseTrailersSanitizer() {
        return responseTrailersSanitizer;
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
    public AbstractLogFormatterBuilder<T> headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> headersSanitizer) {
        requireNonNull(headersSanitizer, "headersSanitizer");
        requestHeadersSanitizer(headersSanitizer);
        requestTrailersSanitizer(headersSanitizer);
        responseHeadersSanitizer(headersSanitizer);
        responseTrailersSanitizer(headersSanitizer);
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an GPS location query, before logging.
     * If unset, will not sanitize request content.
     */
    public AbstractLogFormatterBuilder<T> requestContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends T> requestContentSanitizer) {
        this.requestContentSanitizer = requireNonNull(requestContentSanitizer, "requestContentSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request content before logging.
     */
    @Nullable
    final BiFunction<? super RequestContext, Object, ? extends T> requestContentSanitizer() {
        return requestContentSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset,
     * will not sanitize response content.
     */
    public AbstractLogFormatterBuilder<T> responseContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends T> responseContentSanitizer) {
        this.responseContentSanitizer = requireNonNull(responseContentSanitizer, "responseContentSanitizer");
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response content before logging.
     */
    @Nullable
    final BiFunction<? super RequestContext, Object, ? extends T> responseContentSanitizer() {
        return responseContentSanitizer;
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
    public AbstractLogFormatterBuilder<T> contentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends T> contentSanitizer) {
        requireNonNull(contentSanitizer, "contentSanitizer");
        requestContentSanitizer(contentSanitizer);
        responseContentSanitizer(contentSanitizer);
        return this;
    }
}
