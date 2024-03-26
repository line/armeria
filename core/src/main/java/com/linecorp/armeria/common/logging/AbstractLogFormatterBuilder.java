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
    private HeadersSanitizer<T> requestHeadersSanitizer;

    @Nullable
    private HeadersSanitizer<T> responseHeadersSanitizer;

    @Nullable
    private HeadersSanitizer<T> requestTrailersSanitizer;

    @Nullable
    private HeadersSanitizer<T> responseTrailersSanitizer;

    @Nullable
    private BiFunction<? super RequestContext, Object, ? extends T> requestContentSanitizer;

    @Nullable
    private BiFunction<? super RequestContext, Object, ? extends T> responseContentSanitizer;

    /**
     * Sets the {@link BiFunction} to use to sanitize request headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will
     * not sanitize request headers.
     *
     * <pre>{@code
     * HeadersSanitizer<String> headersSanitizer =
     *   HeadersSanitizer
     *     .builderForText()
     *     .sensitiveHeaders("Authorization", "Cookie")
     *     ...
     *     .build();
     *
     *  LogFormatter
     *    .builderForText()
     *    .requestHeadersSanitizer(headersSanitizer)
     *    ...
     * }</pre>
     */
    public AbstractLogFormatterBuilder<T> requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestHeadersSanitizer) {
        requireNonNull(requestHeadersSanitizer, "requestHeadersSanitizer");
        // TODO(ikhoon): Replace BiFunction with HeadersSanitizer in Armeria 2.0.
        this.requestHeadersSanitizer = requestHeadersSanitizer::apply;
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request headers before logging.
     */
    @Nullable
    final HeadersSanitizer<T> requestHeadersSanitizer() {
        return requestHeadersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will not sanitize response headers.
     *
     * <pre>{@code
     * HeadersSanitizer<String> headersSanitizer =
     *   HeadersSanitizer
     *     .builderForText()
     *     .sensitiveHeaders("Set-Cookie")
     *     ...
     *     .build();
     *
     *  LogFormatter
     *    .builderForText()
     *    .responseHeadersSanitizer(headersSanitizer)
     *    ...
     * }</pre>
     */
    public AbstractLogFormatterBuilder<T> responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseHeadersSanitizer) {
        // TODO(ikhoon): Replace BiFunction with HeadersSanitizer in Armeria 2.0.
        requireNonNull(responseHeadersSanitizer, "responseHeadersSanitizer");
        this.responseHeadersSanitizer = responseHeadersSanitizer::apply;
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize response headers before logging.
     */
    @Nullable
    final HeadersSanitizer<T> responseHeadersSanitizer() {
        return responseHeadersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request trailers before logging. If unset,
     * will not sanitize request trailers.
     *
     * <pre>{@code
     * HeadersSanitizer<String> headersSanitizer =
     *   HeadersSanitizer
     *     .builderForText()
     *     .sensitiveHeaders("...")
     *     ...
     *     .build();
     *
     *  LogFormatter
     *    .builderForText()
     *    .requestTrailersSanitizer(headersSanitizer)
     *    ...
     * }</pre>
     */
    public AbstractLogFormatterBuilder<T> requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> requestTrailersSanitizer) {
        // TODO(ikhoon): Replace BiFunction with HeadersSanitizer in Armeria 2.0.
        requireNonNull(requestTrailersSanitizer, "requestTrailersSanitizer");
        this.requestTrailersSanitizer = requestTrailersSanitizer::apply;
        return this;
    }

    /**
     * Returns the {@link BiFunction} to use to sanitize request trailers before logging.
     */
    @Nullable
    final HeadersSanitizer<T> requestTrailersSanitizer() {
        return requestTrailersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response trailers before logging. If unset,
     * will not sanitize response trailers.
     *
     * <pre>{@code
     * HeadersSanitizer<String> headersSanitizer =
     *   HeadersSanitizer
     *     .builderForText()
     *     .sensitiveHeaders("...")
     *     ...
     *     .build();
     *
     *  LogFormatter
     *    .builderForText()
     *    .responseTrailersSanitizer(headersSanitizer)
     *    ...
     * }</pre>
     */
    public AbstractLogFormatterBuilder<T> responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends T> responseTrailersSanitizer) {
        // TODO(ikhoon): Replace BiFunction with HeadersSanitizer in Armeria 2.0.
        requireNonNull(responseTrailersSanitizer, "responseTrailersSanitizer");
        this.responseTrailersSanitizer = responseTrailersSanitizer::apply;
        return this;
    }

    /**
     * Returns the {@link Function} to use to sanitize response trailers before logging.
     */
    @Nullable
    final HeadersSanitizer<T> responseTrailersSanitizer() {
        return responseTrailersSanitizer;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request, response and trailers before logging.
     * It is common to have the {@link BiFunction} that removes sensitive headers, like {@code "Cookie"} and
     * {@code "Set-Cookie"}, before logging. This method is a shortcut for:
     * <pre>{@code
     * HeadersSanitizer<String> headersSanitizer =
     *   HeadersSanitizer
     *     .builderForText()
     *     .sensitiveHeaders("...")
     *     ...
     *     .build();
     *
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
