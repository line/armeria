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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;

/**
 * Builds a new {@link EncodingService} or its decorator function.
 */
public final class EncodingServiceBuilder {

    private static final Set<MediaType> defaultEncodableMediaTypsSet =
            ImmutableSet.of(MediaType.ANY_TEXT_TYPE,
                            MediaType.APPLICATION_XML_UTF_8,
                            MediaType.JAVASCRIPT_UTF_8,
                            MediaType.JSON_UTF_8);

    // TODO(minwoox) consider this condition to align with the default text previewer.
    private static final Predicate<MediaType> defaultEncodableContentTypePredicate =
            contentType -> {
                for (MediaType encodableMediaType : defaultEncodableMediaTypsSet) {
                    if (contentType.belongsTo(encodableMediaType)) {
                        return true;
                    }
                }
                return false;
            };

    private static final int DEFAULT_MIN_BYTES_TO_FORCE_CHUNKED_AND_ENCODING = 1024;

    private Predicate<MediaType> encodableContentTypePredicate = defaultEncodableContentTypePredicate;

    private Predicate<? super RequestHeaders> encodableRequestHeadersPredicate = headers -> true;

    private int minBytesToForceChunkedAndEncoding = DEFAULT_MIN_BYTES_TO_FORCE_CHUNKED_AND_ENCODING;

    EncodingServiceBuilder() {}

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
        final List<MediaType> snapshot = ImmutableList.copyOf(requireNonNull(contentTypes, "contentTypes"));
        return encodableContentTypes(mediaType -> snapshot.stream().anyMatch(mediaType::belongsTo));
    }

    /**
     * Sets the specified {@link Predicate} to evaluate whether the content type of the {@link HttpResponse} is
     * encodable or not.
     */
    public EncodingServiceBuilder encodableContentTypes(
            Predicate<MediaType> encodableContentTypePredicate) {
        requireNonNull(encodableContentTypePredicate, "encodableContentTypePredicate");
        this.encodableContentTypePredicate = encodableContentTypePredicate;
        return this;
    }

    /**
     * Sets the specified {@link Predicate} to evaluate whether the corresponding {@link HttpResponse} of the
     * {@link HttpRequest} whose {@link RequestHeaders} is the input of the {@link Predicate}
     * is encodable or not.
     */
    public EncodingServiceBuilder encodableRequestHeaders(
            Predicate<? super RequestHeaders> encodableRequestHeadersPredicate) {
        requireNonNull(encodableRequestHeadersPredicate, "encodableRequestHeadersPredicate");
        this.encodableRequestHeadersPredicate = encodableRequestHeadersPredicate;
        return this;
    }

    /**
     * Sets the specified minimum length to force chunked encoding. The {@link HttpResponse} is encoded only
     * when the content is variable, which means the {@link ResponseHeaders} does not have
     * {@code "Content-Length"} header, or the length of the content exceeds the specified length.
     * The default is {@value DEFAULT_MIN_BYTES_TO_FORCE_CHUNKED_AND_ENCODING}.
     */
    public EncodingServiceBuilder minBytesToForceChunkedEncoding(int minBytesToForceChunkedAndEncoding) {
        checkArgument(minBytesToForceChunkedAndEncoding > 0,
                      "minBytesToForceChunkedAndEncoding: %s (expected: > 0)",
                      minBytesToForceChunkedAndEncoding);
        this.minBytesToForceChunkedAndEncoding = minBytesToForceChunkedAndEncoding;
        return this;
    }

    /**
     * Returns a newly-created {@link EncodingService} based on the properties of this builder.
     */
    public EncodingService build(HttpService delegate) {
        return new EncodingService(delegate, encodableContentTypePredicate, encodableRequestHeadersPredicate,
                                   minBytesToForceChunkedAndEncoding);
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpService} with a new
     * {@link EncodingService} based on the properties of this builder.
     */
    public Function<? super HttpService, EncodingService> newDecorator() {
        return this::build;
    }
}
