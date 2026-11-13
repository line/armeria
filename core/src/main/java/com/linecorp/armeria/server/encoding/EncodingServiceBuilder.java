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

package com.linecorp.armeria.server.encoding;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;
import com.linecorp.armeria.server.HttpService;

/**
 * Builds a new {@link EncodingService} or its decorator function.
 */
public final class EncodingServiceBuilder {

    private final CompressionBuilder builder = Compression.builder();

    EncodingServiceBuilder() {}

    /**
     * Sets the {@link StreamEncoderFactory}s to use. If not specified, {@link StreamEncoderFactory#all()}
     * is used by default.
     *
     * <p>
     *     The configured {@link EncodingService} decides on a {@link StreamEncoderFactory}
     *     depending on the weight of their {@link StreamEncoderFactory#encodingHeaderValue()} in the
     *     {@link HttpHeaderNames#ACCEPT_ENCODING} header.
     *     See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.4">RFC 7231</a> for details.
     * </p>
     */
    @UnstableApi
    public EncodingServiceBuilder encoderFactories(StreamEncoderFactory... encoderFactories) {
        requireNonNull(encoderFactories, "encoderFactories");
        return encoderFactories(ImmutableList.copyOf(encoderFactories));
    }

    /**
     * Sets the {@link StreamEncoderFactory}s to use. If not specified, {@link StreamEncoderFactory#all()}
     * is used by default.
     *
     * <p>
     *     The configured {@link EncodingService} decides on a {@link StreamEncoderFactory}
     *     depending on the weight of their {@link StreamEncoderFactory#encodingHeaderValue()} in the
     *     {@link HttpHeaderNames#ACCEPT_ENCODING} header.
     *     See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.4">RFC 7231</a> for details.
     * </p>
     */
    @UnstableApi
    public EncodingServiceBuilder encoderFactories(Iterable<? extends StreamEncoderFactory> encoderFactories) {
        builder.encoderFactories(encoderFactories);
        return this;
    }

    /**
     * Sets the specified {@link MediaType}s to evaluate whether the content type of the {@link HttpResponse}
     * is encodable or not. It's encodable when the content type is one of the {@link MediaType}s.
     */
    public EncodingServiceBuilder encodableContentTypes(MediaType... contentTypes) {
        return encodableContentTypes(ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes")));
    }

    /**
     * Sets the specified {@link MediaType}s to evaluate whether the content type of the {@link HttpResponse}
     * is encodable or not. It's encodable when the content type is one of the {@link MediaType}s.
     */
    public EncodingServiceBuilder encodableContentTypes(Iterable<MediaType> contentTypes) {
        builder.encodableContentTypes(contentTypes);
        return this;
    }

    /**
     * Sets the specified {@link Predicate} to evaluate whether the content type of the {@link HttpResponse} is
     * encodable or not.
     */
    public EncodingServiceBuilder encodableContentTypes(
            Predicate<MediaType> encodableContentTypePredicate) {
        builder.encodableContentTypes(encodableContentTypePredicate);
        return this;
    }

    /**
     * Sets the specified {@link Predicate} to evaluate whether the corresponding {@link HttpResponse} of the
     * {@link HttpRequest} whose {@link RequestHeaders} is the input of the {@link Predicate}
     * is encodable or not.
     */
    public EncodingServiceBuilder encodableRequestHeaders(
            Predicate<? super RequestHeaders> encodableRequestHeadersPredicate) {
        builder.encodableRequestHeaders(encodableRequestHeadersPredicate);
        return this;
    }

    /**
     * Sets the specified minimum length to force chunked encoding. The {@link HttpResponse} is encoded only
     * when the content is variable, which means the {@link ResponseHeaders} does not have
     * {@code "Content-Length"} header, or the length of the content exceeds the specified length.
     * The default is {@code 1024}.
     */
    public EncodingServiceBuilder minBytesToForceChunkedEncoding(int minBytesToForceChunkedAndEncoding) {
        builder.minBytesToForceChunkedEncoding(minBytesToForceChunkedAndEncoding);
        return this;
    }

    /**
     * Returns a newly-created {@link EncodingService} based on the properties of this builder.
     */
    public EncodingService build(HttpService delegate) {
        return new EncodingService(delegate, builder.build());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpService} with a new
     * {@link EncodingService} based on the properties of this builder.
     */
    public Function<? super HttpService, EncodingService> newDecorator() {
        return this::build;
    }
}
