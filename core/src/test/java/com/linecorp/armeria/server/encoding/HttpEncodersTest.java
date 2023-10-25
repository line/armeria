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

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.encoding.StreamEncoderFactories;

import io.netty.handler.codec.compression.Brotli;

class HttpEncodersTest {
    @Test
    void noAcceptEncoding() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isNull();
    }

    @Test
    void acceptEncodingGzip() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
    }

    @Test
    void acceptEncodingDeflate() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "deflate");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.DEFLATE);
    }

    @Test
    void acceptEncodingBrotli() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "br");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(
                Brotli.isAvailable() ? StreamEncoderFactories.BROTLI : null);
    }

    @Test
    void acceptEncodingSnappyFraming() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "x-snappy-framed");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.SNAPPY);
    }

    @Test
    void acceptEncodingAllOfThree() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "gzip, deflate, br");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
    }

    @Test
    void acceptEncodingBoth() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
    }

    @Test
    void acceptEncodingUnknown() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "piedpiper");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isNull();
    }

    @Test
    void acceptEncodingWithQualityValues() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "br;q=0.8, deflate, gzip;q=0.7, *;q=0.1");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.DEFLATE);
    }

    @Test
    void acceptEncodingOrder() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "deflate;q=0.5, gzip;q=0.9, br;q=0.9");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.GZIP);
    }

    @Test
    void acceptEncodingWithZeroValues() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "gzip;q=0.0, br;q=0.0, *;q=0.1");
        assertThat(HttpEncoders.getEncoderFactory(headers)).isEqualTo(StreamEncoderFactories.DEFLATE);
    }
}
