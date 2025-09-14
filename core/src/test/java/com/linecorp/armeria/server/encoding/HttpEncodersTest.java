/*
 * Copyright 2016 LINE Corporation
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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.encoding.StreamEncoderFactories;

import io.netty.handler.codec.compression.Brotli;

class HttpEncodersTest {
    @Test
    void noAcceptEncoding() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isNull();
        assertThat(HttpEncoders.getEncoderFactory(headers, HttpEncoders.ALL_ENCODER_FACTORIES)).isNull();
        assertThat(HttpEncoders.getEncoderFactory(headers, ImmutableSet.of())).isNull();
        assertThat(HttpEncoders.getEncoderFactory(
                           headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY)
                   )
        ).isNull();
    }

    @Test
    void acceptEncodingGzip() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.GZIP, StreamEncoderFactories.SNAPPY)
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // GZIP is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY, StreamEncoderFactories.DEFLATE)
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
    }

    @Test
    void acceptEncodingDeflate() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "deflate");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.DEFLATE);
        assertThat(HttpEncoders.getEncoderFactory(headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE, StreamEncoderFactories.SNAPPY)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // DEFLATE is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY, StreamEncoderFactories.GZIP)
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
    }

    @Test
    void acceptEncodingBrotli() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "br");
        @Nullable
        final StreamEncoderFactories expectedBrotliFactory =
                Brotli.isAvailable() ? StreamEncoderFactories.BROTLI : null;

        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(expectedBrotliFactory);
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.BROTLI, StreamEncoderFactories.SNAPPY)
        )).isEqualTo(expectedBrotliFactory);
        // BROTLI is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY, StreamEncoderFactories.GZIP)
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
    }

    @Test
    void acceptEncodingSnappyFraming() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "x-snappy-framed");

        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.SNAPPY);
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY, StreamEncoderFactories.GZIP)
        )).isEqualTo(StreamEncoderFactories.SNAPPY);
        // SNAPPY is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.GZIP, StreamEncoderFactories.DEFLATE)
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
    }

    @Test
    void acceptEncodingAllOfThree() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "gzip, deflate, br");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
        assertThat(HttpEncoders.getEncoderFactory(headers, ImmutableSet.of(StreamEncoderFactories.GZIP,
                                                                           StreamEncoderFactories.DEFLATE,
                                                                           StreamEncoderFactories.BROTLI)
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // GZIP is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE, StreamEncoderFactories.BROTLI)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // GZIP and BROTLI are not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // GZIP and DEFLATE are not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.BROTLI)
        )).isEqualTo(Brotli.isAvailable() ? StreamEncoderFactories.BROTLI : null);
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY)
        )).isNull();
    }

    @Test
    void acceptEncodingBoth() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
        assertThat(HttpEncoders.getEncoderFactory(headers, ImmutableSet.of(StreamEncoderFactories.GZIP,
                                                                           StreamEncoderFactories.DEFLATE)
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // Only GZIP is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.GZIP)
        )).isEqualTo(StreamEncoderFactories.GZIP);

        // GZIP is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);

        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY)
        )).isNull();
    }

    @Test
    void acceptEncodingUnknown() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "piedpiper");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isNull();
        // All are on the allow list.
        assertThat(HttpEncoders.getEncoderFactory(headers, HttpEncoders.ALL_ENCODER_FACTORIES)).isNull();
    }

    @Test
    void acceptEncodingWithQualityValues() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "br;q=0.8, deflate, gzip;q=0.7, *;q=0.1");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.DEFLATE);
        assertThat(HttpEncoders.getEncoderFactory(headers, ImmutableSet.of(StreamEncoderFactories.GZIP,
                                                                           StreamEncoderFactories.DEFLATE,
                                                                           StreamEncoderFactories.BROTLI)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // DEFLATE is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.GZIP, StreamEncoderFactories.BROTLI)
        )).isEqualTo(
                Brotli.isAvailable() ? StreamEncoderFactories.BROTLI : StreamEncoderFactories.GZIP);
        // DEFLATE and BROTLI are not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.GZIP)
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // SNAPPY is in the allow list and selected by wildcard.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY)
        )).isEqualTo(StreamEncoderFactories.SNAPPY);
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
    }

    @Test
    void acceptEncodingOrder() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "deflate;q=0.5, gzip;q=0.9, br;q=0.9");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
        assertThat(HttpEncoders.getEncoderFactory(headers, ImmutableSet.of(StreamEncoderFactories.GZIP,
                                                                           StreamEncoderFactories.DEFLATE,
                                                                           StreamEncoderFactories.BROTLI)
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // GZIP is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE, StreamEncoderFactories.BROTLI)
        )).isEqualTo(StreamEncoderFactories.BROTLI);
        // GZIP and BROTLI are not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // SNAPPY is in the allow list and not selected as there is no wildcard.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY)
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
    }

    @Test
    void acceptEncodingWithZeroValues() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "gzip;q=0.0, br;q=0.0, *;q=0.1");

        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.DEFLATE);
        assertThat(HttpEncoders.getEncoderFactory(headers, ImmutableSet.of(StreamEncoderFactories.GZIP,
                                                                           StreamEncoderFactories.DEFLATE,
                                                                           StreamEncoderFactories.BROTLI)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // GZIP is not in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.DEFLATE, StreamEncoderFactories.BROTLI)
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // SNAPPY and DEFLATE are in the allow list and both could be selected by wildcard.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY, StreamEncoderFactories.DEFLATE)
        )).isIn(StreamEncoderFactories.SNAPPY, StreamEncoderFactories.DEFLATE);
        // Only BROTLI is in the allow list but not selected as its quality value is 0.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.BROTLI)
        )).isNull();
        // Only SNAPPY is in the allow list and selected by wildcard.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of(StreamEncoderFactories.SNAPPY)
        )).isEqualTo(StreamEncoderFactories.SNAPPY);
        // None is in the allow list.
        assertThat(HttpEncoders.getEncoderFactory(
                headers, ImmutableSet.of()
        )).isNull();
    }
}
