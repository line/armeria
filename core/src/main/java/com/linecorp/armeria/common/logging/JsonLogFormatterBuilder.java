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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A builder implementation for {@link TextLogFormatter}
 */
@UnstableApi
public class JsonLogFormatterBuilder extends AbstractLogFormatterBuilder<JsonNode> {

    private static <T, U> BiFunction<T, U, JsonNode> DEFAULT_SANITIZER(ObjectMapper objectMapper) {
        return (first, second) -> objectMapper.valueToTree(second);
    }

    @Nullable
    private ObjectMapper objectMapper;

    JsonLogFormatterBuilder() {}

    /**
     * Sets the {@link ObjectMapper} that will be used to convert into a json format message.
     */
    public JsonLogFormatterBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper");
        return this;
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Cookie}, before logging. If unset, will
     * not sanitize request headers.
     */
    @Override
    public JsonLogFormatterBuilder requestHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode> requestHeadersSanitizer) {
        return (JsonLogFormatterBuilder) super.requestHeadersSanitizer(requestHeadersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response headers before logging. It is common to have the
     * {@link BiFunction} that removes sensitive headers, like {@code Set-Cookie}, before logging. If unset,
     * will not sanitize response headers.
     */
    @Override
    public JsonLogFormatterBuilder responseHeadersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode> responseHeadersSanitizer) {
        return (JsonLogFormatterBuilder) super.responseHeadersSanitizer(responseHeadersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request trailers before logging. If unset,
     * will not sanitize request trailers.
     */
    @Override
    public JsonLogFormatterBuilder requestTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode> requestTrailersSanitizer) {
        return (JsonLogFormatterBuilder) super.requestTrailersSanitizer(requestTrailersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response trailers before logging. If unset,
     * will not sanitize response trailers.
     */
    @Override
    public JsonLogFormatterBuilder responseTrailersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode> responseTrailersSanitizer) {
        return (JsonLogFormatterBuilder) super.responseTrailersSanitizer(responseTrailersSanitizer);
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
    public JsonLogFormatterBuilder headersSanitizer(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode> headersSanitizer) {
        return (JsonLogFormatterBuilder) super.headersSanitizer(headersSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize request content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an GPS location query, before logging.
     * If unset, will not sanitize request content.
     */
    @Override
    public JsonLogFormatterBuilder requestContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends JsonNode> requestContentSanitizer) {
        return (JsonLogFormatterBuilder) super.requestContentSanitizer(requestContentSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize response content before logging. It is common to have the
     * {@link BiFunction} that removes sensitive content, such as an address, before logging. If unset,
     * will not sanitize response content.
     */
    @Override
    public JsonLogFormatterBuilder responseContentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends JsonNode> responseContentSanitizer) {
        return (JsonLogFormatterBuilder) super.responseContentSanitizer(responseContentSanitizer);
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
    public JsonLogFormatterBuilder contentSanitizer(
            BiFunction<? super RequestContext, Object, ? extends JsonNode> contentSanitizer) {
        return (JsonLogFormatterBuilder) super.contentSanitizer(contentSanitizer);
    }

    /**
     * Sets the {@link BiFunction} to use to sanitize a response cause before logging. You can
     * sanitize the stack trace of the exception to remove sensitive information, or prevent from logging
     * the stack trace completely by returning {@code null} in the {@link BiFunction}. If unset, will not
     * sanitize a response cause.
     */
    @Override
    public JsonLogFormatterBuilder responseCauseSanitizer(
            BiFunction<? super RequestContext, ? super Throwable, ? extends JsonNode> responseCauseSanitizer) {
        return (JsonLogFormatterBuilder) super.responseCauseSanitizer(responseCauseSanitizer);
    }

    /**
     * Returns a newly-created {@link JsonLogFormatter} based on the properties of this builder.
     */
    public JsonLogFormatter build() {
        final ObjectMapper objectMapper = this.objectMapper != null ?
                                          this.objectMapper : JacksonUtil.newDefaultObjectMapper();
        final BiFunction<RequestContext, HttpHeaders, JsonNode> DEFAULT_HEADERS_SANITIZER =
                DEFAULT_SANITIZER(objectMapper);
        final BiFunction<RequestContext, Object, JsonNode> DEFAULT_CONTENT_SANITIZER =
                DEFAULT_SANITIZER(objectMapper);
        final BiFunction<RequestContext, Throwable, JsonNode> DEFAULT_CAUSE_SANITIZER =
                DEFAULT_SANITIZER(objectMapper);
        return new JsonLogFormatter(
                requestHeadersSanitizer() != null ? requestHeadersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                responseHeadersSanitizer() != null ? responseHeadersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                requestTrailersSanitizer() != null ? requestTrailersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                responseTrailersSanitizer() != null ? responseTrailersSanitizer() : DEFAULT_HEADERS_SANITIZER,
                requestContentSanitizer() != null ? requestContentSanitizer() : DEFAULT_CONTENT_SANITIZER,
                responseContentSanitizer() != null ? responseContentSanitizer() : DEFAULT_CONTENT_SANITIZER,
                responseCauseSanitizer() != null ? responseCauseSanitizer() : DEFAULT_CAUSE_SANITIZER,
                objectMapper);
    }
}
