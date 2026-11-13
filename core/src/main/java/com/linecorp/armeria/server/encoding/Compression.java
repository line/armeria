/*
 * Copyright 2024 LINE Corporation
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

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;

/**
 * An immutable configuration for server response compression.
 */
@UnstableApi
public final class Compression {
    private static final Compression DISABLED =
            new Compression(ImmutableList.of(), type -> false, headers -> false, 1024);

    /**
     * Returns a new builder with the defaults used by {@link EncodingService}.
     */
    public static CompressionBuilder builder() {
        return new CompressionBuilder();
    }

    /**
     * Returns a configuration that disables compression.
     */
    public static Compression disabled() {
        return DISABLED;
    }

    private final Map<String, StreamEncoderFactory> encoderFactories;
    private final Predicate<MediaType> encodableContentTypes;
    private final Predicate<? super RequestHeaders> encodableRequestHeaders;
    private final int minBytesToForceChunkedEncoding;

    Compression(List<StreamEncoderFactory> encoderFactories, Predicate<MediaType> encodableContentTypes,
                Predicate<? super RequestHeaders> encodableRequestHeaders, int minBytesToForceChunkedEncoding) {
        final ImmutableMap.Builder<String, StreamEncoderFactory> builder = ImmutableMap.builder();
        for (StreamEncoderFactory factory : encoderFactories) {
            builder.put(factory.encodingHeaderValue(), factory);
        }
        this.encoderFactories = builder.buildOrThrow();
        this.encodableContentTypes = requireNonNull(encodableContentTypes, "encodableContentTypes");
        this.encodableRequestHeaders = requireNonNull(encodableRequestHeaders, "encodableRequestHeaders");
        this.minBytesToForceChunkedEncoding = minBytesToForceChunkedEncoding;
    }

    /**
     * Returns the available encoders keyed by content encoding, or an empty map if compression is disabled.
     */
    public Map<String, StreamEncoderFactory> encoderFactories() {
        return encoderFactories;
    }

    /**
     * Returns the predicate for content types that may be compressed.
     */
    public Predicate<MediaType> encodableContentTypes() {
        return encodableContentTypes;
    }

    /**
     * Returns the predicate for requests whose responses may be compressed.
     */
    public Predicate<? super RequestHeaders> encodableRequestHeaders() {
        return encodableRequestHeaders;
    }

    /**
     * Returns the minimum known content length to compress. Unknown lengths may be compressed.
     */
    public int minBytesToForceChunkedEncoding() {
        return minBytesToForceChunkedEncoding;
    }
}
